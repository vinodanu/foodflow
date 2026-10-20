package com.foodflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

record PaymentView(String id, String orderId, String providerReference, String amount, String currency, String status) {}
record PaymentResult(PaymentView payment, boolean duplicate) {}
record PaymentEvent(String eventId, String paymentId, String providerReference, String outcome) {}
record SandboxOutcome(@NotBlank String outcome) {}

@Service
class Payments {
    private final JdbcTemplate db;
    private final Catalog catalog;
    private final String webhookSecret;
    private final boolean sandboxEnabled;

    Payments(JdbcTemplate db, Catalog catalog,
            @Value("${foodflow.payment-webhook-secret}") String webhookSecret,
            @Value("${foodflow.sandbox-payments:true}") boolean sandboxEnabled) {
        this.db = db;
        this.catalog = catalog;
        this.webhookSecret = webhookSecret;
        this.sandboxEnabled = sandboxEnabled;
    }

    private void require(boolean condition, int status, String message) {
        if (!condition) throw new Failure(status, message);
    }

    private boolean role(Authentication auth, String role) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    private String money(BigDecimal amount) { return amount.setScale(2).toPlainString(); }

    private PaymentView view(String id) {
        return db.query("SELECT * FROM payments WHERE id=?", (rs, n) -> new PaymentView(
                rs.getString("id"), rs.getString("order_id"), rs.getString("provider_reference"),
                money(rs.getBigDecimal("amount")), rs.getString("currency"), rs.getString("status")), id)
                .stream().findFirst().orElseThrow(() -> new Failure(404, "Payment not found"));
    }

    @Transactional
    PaymentView create(Authentication auth, String orderId, String key) {
        require(role(auth, "CUSTOMER"), 403, "Only customers can start a payment");
        require(key != null && key.matches("[A-Za-z0-9_-]{8,100}"), 400, "Supply an Idempotency-Key of 8–100 letters, digits, underscores or hyphens");
        var order = db.query("SELECT customer,status,total,payment_status FROM orders WHERE id=?",
                (rs, n) -> Map.of("customer", rs.getString("customer"), "status", rs.getString("status"),
                        "total", rs.getBigDecimal("total"), "paymentStatus", rs.getString("payment_status")), orderId)
                .stream().findFirst().orElseThrow(() -> new Failure(404, "Order not found"));
        require(auth.getName().equals(order.get("customer")), 404, "Order not found");
        require("PAYMENT_PENDING".equals(order.get("status")), 409, "Order is not awaiting payment");
        var existing = db.queryForList("SELECT id FROM payments WHERE order_id=? AND idempotency_key=?", orderId, key);
        if (!existing.isEmpty()) return view(existing.getFirst().get("id").toString());
        var active = db.queryForList("SELECT id FROM payments WHERE order_id=? AND status IN ('PENDING','INTERRUPTED') ORDER BY created_at DESC", orderId);
        if (!active.isEmpty()) return view(active.getFirst().get("id").toString());
        String id = UUID.randomUUID().toString();
        String reference = "sandbox_" + UUID.randomUUID();
        String now = Instant.now().toString();
        db.update("INSERT INTO payments(id,order_id,provider_reference,amount,currency,status,idempotency_key,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id, orderId, reference, order.get("total"), "INR", "PENDING", key, now, now);
        db.update("UPDATE orders SET payment_status='PENDING' WHERE id=?", orderId);
        return view(id);
    }

    String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Could not sign sandbox payment callback", e); }
    }

    @Transactional
    PaymentResult callback(String body, String signature) {
        require(signature != null, 401, "Missing payment signature");
        String supplied = signature.startsWith("sha256=") ? signature.substring(7) : signature;
        require(MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII)), 401, "Invalid payment signature");
        final PaymentEvent event;
        try { event = catalog.json.readValue(body, PaymentEvent.class); }
        catch (Exception e) { throw new Failure(400, "Malformed payment callback"); }
        require(event.eventId() != null && event.eventId().matches("[A-Za-z0-9_-]{8,100}"), 400, "Invalid payment event id");
        require(event.paymentId() != null && event.providerReference() != null && event.outcome() != null && List.of("SUCCEEDED", "FAILED", "INTERRUPTED").contains(event.outcome()), 422, "Unsupported payment outcome");
        var paymentRows = db.queryForList("SELECT * FROM payments WHERE id=? FOR UPDATE", event.paymentId());
        require(!paymentRows.isEmpty(), 404, "Payment not found");
        var payment = paymentRows.getFirst();
        require(payment.get("provider_reference").equals(event.providerReference()), 422, "Provider reference mismatch");
        String fingerprint;
        try { fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
        var prior = db.queryForList("SELECT payload_hash,payment_id FROM payment_events WHERE event_id=?", event.eventId());
        if (!prior.isEmpty()) {
            require(fingerprint.equals(prior.getFirst().get("payload_hash")) && event.paymentId().equals(prior.getFirst().get("payment_id")), 409, "Payment event id was reused with different content");
            return new PaymentResult(view(event.paymentId()), true);
        }
        String current = payment.get("status").toString();
        require(current.equals("PENDING") || current.equals("INTERRUPTED"), 409, "Payment is already final");
        String now = Instant.now().toString();
        db.update("INSERT INTO payment_events(event_id,payment_id,payload_hash,outcome,received_at) VALUES(?,?,?,?,?)", event.eventId(), event.paymentId(), fingerprint, event.outcome(), now);
        db.update("UPDATE payments SET status=?,updated_at=? WHERE id=?", event.outcome(), now, event.paymentId());
        String payStatus = switch (event.outcome()) { case "SUCCEEDED" -> "PAID"; case "FAILED" -> "FAILED"; default -> "INTERRUPTED"; };
        int updated;
        if (event.outcome().equals("SUCCEEDED")) {
            int timeout=db.queryForObject("SELECT acceptance_timeout_seconds FROM restaurants WHERE id=?",Integer.class,
                    db.queryForObject("SELECT restaurant_id FROM orders WHERE id=?",String.class,payment.get("order_id")));
            String acceptBy=Instant.now().plusSeconds(timeout).toString();
            updated=db.update("UPDATE orders SET status='PLACED',payment_status='PAID',accept_by=?,version=version+1 WHERE id=? AND status='PAYMENT_PENDING'",acceptBy,payment.get("order_id"));
        } else {
            updated=db.update("UPDATE orders SET payment_status=?,version=version+1 WHERE id=? AND status='PAYMENT_PENDING'",payStatus,payment.get("order_id"));
        }
        if (event.outcome().equals("SUCCEEDED") && updated == 0) {
            String orderStatus = db.queryForObject("SELECT status FROM orders WHERE id=?", String.class, payment.get("order_id"));
            require(List.of("CANCELLED", "REJECTED").contains(orderStatus), 409, "Order is no longer eligible for this payment");
            db.update("UPDATE orders SET payment_status='PAID',version=version+1 WHERE id=?", payment.get("order_id"));
            refundIfPaid(payment.get("order_id").toString());
        }
        String note = "Sandbox provider callback: " + event.outcome();
        String actionId = UUID.randomUUID().toString();
        db.update("INSERT INTO order_actions VALUES(?,?,?,?,?,?)", actionId, payment.get("order_id"), "payment-sandbox", "Payment" + event.outcome(), now, note);
        String customer=db.queryForObject("SELECT customer FROM orders WHERE id=?",String.class,payment.get("order_id"));
        db.update("INSERT INTO customer_notifications(id,action_id,customer,order_id,title,message,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),actionId,customer,payment.get("order_id"),"Payment "+event.outcome().toLowerCase(Locale.ROOT),note,now);
        return new PaymentResult(view(event.paymentId()), false);
    }

    @Transactional
    PaymentResult simulate(Authentication auth, String paymentId, String outcome) {
        require(sandboxEnabled, 403, "Sandbox payment simulation is disabled");
        require(List.of("SUCCEEDED", "FAILED", "INTERRUPTED").contains(outcome), 422, "Unsupported sandbox outcome");
        PaymentView p = view(paymentId);
        require(role(auth, "CUSTOMER") && db.queryForObject("SELECT customer FROM orders WHERE id=?", String.class, p.orderId()).equals(auth.getName()), 404, "Payment not found");
        String body;
        try { body = catalog.json.writeValueAsString(new PaymentEvent("sandbox_" + UUID.randomUUID(), p.id(), p.providerReference(), outcome)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return callback(body, sign(body));
    }

    @Transactional(readOnly = true)
    List<PaymentView> reconciliation(Authentication auth) {
        require(role(auth, "ADMIN"), 403, "Only operations can view reconciliation");
        return db.query("SELECT id FROM payments WHERE status IN ('PENDING','INTERRUPTED') ORDER BY created_at",
                (rs, n) -> view(rs.getString(1)));
    }

    @Transactional
    PaymentResult reconcile(Authentication auth, String paymentId, String outcome) {
        require(role(auth, "ADMIN"), 403, "Only operations can reconcile payments");
        require(sandboxEnabled && List.of("SUCCEEDED", "FAILED", "INTERRUPTED").contains(outcome), 422, "Unsupported reconciliation result");
        PaymentView p = view(paymentId);
        String body;
        try { body = catalog.json.writeValueAsString(new PaymentEvent("reconcile_" + UUID.randomUUID(), p.id(), p.providerReference(), outcome)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return callback(body, sign(body));
    }

    @Transactional
    String refundIfPaid(String orderId) {
        var row = db.queryForList("SELECT id,amount FROM payments WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1", orderId);
        if (row.isEmpty()) return "NONE";
        var existing = db.queryForList("SELECT status FROM refunds WHERE order_id=?", orderId);
        if (!existing.isEmpty()) return existing.getFirst().get("status").toString();
        String now = Instant.now().toString();
        db.update("INSERT INTO refunds(id,order_id,payment_id,amount,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), orderId, row.getFirst().get("id"), row.getFirst().get("amount"), "REFUNDED", now, now);
        db.update("UPDATE orders SET refund_status='REFUNDED' WHERE id=?", orderId);
        return "REFUNDED";
    }
}

@RestController
@RequestMapping("/api/v1")
class PaymentsController {
    private final Payments payments;
    PaymentsController(Payments payments) { this.payments = payments; }

    @PostMapping("/orders/{orderId}/payments")
    PaymentView create(Authentication auth, @PathVariable String orderId, @RequestHeader(value="Idempotency-Key", required=false) String key) { return payments.create(auth, orderId, key); }

    @PostMapping("/payments/{paymentId}/sandbox-result")
    PaymentResult sandbox(Authentication auth, @PathVariable String paymentId, @Valid @RequestBody SandboxOutcome request) { return payments.simulate(auth, paymentId, request.outcome()); }

    @GetMapping("/payments/reconciliation")
    List<PaymentView> reconciliation(Authentication auth) { return payments.reconciliation(auth); }

    @PostMapping("/payments/{paymentId}/reconcile")
    PaymentResult reconcile(Authentication auth, @PathVariable String paymentId, @Valid @RequestBody SandboxOutcome request) { return payments.reconcile(auth, paymentId, request.outcome()); }

    @PostMapping("/payments/webhook")
    PaymentResult webhook(@RequestBody String body, @RequestHeader("X-Payment-Signature") String signature) { return payments.callback(body, signature); }
}
