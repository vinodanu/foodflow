package com.foodflow;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payments;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.password=","foodflow.demo-operations=true","foodflow.sandbox-payments=true","foodflow.payment-webhook-secret=test-payment-secret"})
class PaymentsTest {
    @Autowired Orders orders;
    @Autowired Payments payments;

    Authentication user(String name, String role) {
        return new UsernamePasswordAuthenticationToken(name, "", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    OrderView pending() {
        return orders.place(user("customer", "CUSTOMER"), UUID.randomUUID().toString(),
                new Checkout("green", "Flat 201, Road 12, Jubilee Hills 500033", "Jubilee Hills, Hyderabad", List.of(new Line("bowl", 1))));
    }

    PaymentView intent(OrderView order) {
        return payments.create(user("customer", "CUSTOMER"), order.id(), UUID.randomUUID().toString());
    }

    @Test void createsIdempotentPaymentAndVerifiesSignedDuplicateCallbacks() {
        var order = pending();
        String key = UUID.randomUUID().toString();
        var first = payments.create(user("customer", "CUSTOMER"), order.id(), key);
        var retry = payments.create(user("customer", "CUSTOMER"), order.id(), key);
        var parallelRetry = payments.create(user("customer", "CUSTOMER"), order.id(), UUID.randomUUID().toString());
        assertEquals(first.id(), retry.id());
        assertEquals(first.id(), parallelRetry.id());
        assertEquals("284.00", first.amount());

        String body = "{\"eventId\":\"event_success_123\",\"paymentId\":\"" + first.id() + "\",\"providerReference\":\"" + first.providerReference() + "\",\"outcome\":\"SUCCEEDED\"}";
        var applied = payments.callback(body, payments.sign(body));
        var duplicate = payments.callback(body, payments.sign(body));
        assertFalse(applied.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals("SUCCEEDED", duplicate.payment().status());
        assertEquals("PAID", orders.get(user("customer", "CUSTOMER"), order.id()).paymentStatus());
        assertEquals(401, assertThrows(Failure.class, () -> payments.callback(body, "incorrect")).status);
    }

    @Test void failedAndInterruptedAttemptsRemainReconcileable() {
        var failedOrder = pending();
        var failedPayment = intent(failedOrder);
        payments.simulate(user("customer", "CUSTOMER"), failedPayment.id(), "FAILED");
        assertFalse(payments.reconciliation(user("admin", "ADMIN")).stream().anyMatch(p -> p.id().equals(failedPayment.id())));
        var failed = orders.get(user("customer", "CUSTOMER"), failedOrder.id());
        assertEquals("PAYMENT_PENDING", failed.status());
        assertEquals("FAILED", failed.paymentStatus());

        var interruptedOrder = pending();
        var interruptedPayment = intent(interruptedOrder);
        payments.simulate(user("customer", "CUSTOMER"), interruptedPayment.id(), "INTERRUPTED");
        assertEquals("INTERRUPTED", payments.reconciliation(user("admin", "ADMIN")).stream().filter(p -> p.id().equals(interruptedPayment.id())).findFirst().orElseThrow().status());
        var reconciled = payments.reconcile(user("admin", "ADMIN"), interruptedPayment.id(), "SUCCEEDED");
        assertEquals("SUCCEEDED", reconciled.payment().status());
        assertEquals("PLACED", orders.get(user("customer", "CUSTOMER"), interruptedOrder.id()).status());
    }

    @Test void cancellationAfterSuccessfulSandboxPaymentRefundsOnlyOnce() {
        var order = pending();
        var payment = intent(order);
        payments.simulate(user("customer", "CUSTOMER"), payment.id(), "SUCCEEDED");
        var paid = orders.get(user("customer", "CUSTOMER"), order.id());
        var cancelled = orders.transition(user("customer", "CUSTOMER"), order.id(), new Action("CANCELLED", paid.version(), "Customer cancelled"));
        assertEquals("REFUNDED", cancelled.refundStatus());
        assertEquals("REFUNDED", payments.refundIfPaid(order.id()));

        var pendingOrder = pending();
        var pendingPayment = intent(pendingOrder);
        orders.transition(user("customer", "CUSTOMER"), pendingOrder.id(), new Action("CANCELLED", pendingOrder.version(), "Cancelled during checkout"));
        payments.simulate(user("customer", "CUSTOMER"), pendingPayment.id(), "SUCCEEDED");
        var lateSuccess = orders.get(user("customer", "CUSTOMER"), pendingOrder.id());
        assertEquals("CANCELLED", lateSuccess.status());
        assertEquals("REFUNDED", lateSuccess.refundStatus());
    }
}
