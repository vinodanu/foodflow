package com.foodflow.order;

import com.foodflow.audit.AuditService;
import com.foodflow.catalog.CatalogService;
import com.foodflow.config.AppProperties;
import com.foodflow.domain.model.Action;
import com.foodflow.domain.model.Assign;
import com.foodflow.domain.model.Checkout;
import com.foodflow.domain.model.Line;
import com.foodflow.domain.model.OrderLine;
import com.foodflow.domain.model.OrderView;
import com.foodflow.exception.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JDBC-backed implementation of {@link OrderService}.
 * Contains all order business logic: pricing, idempotency, optimistic locking, role-based transitions.
 */
@Service
public class DefaultOrderService implements OrderService {

    private static final Set<String> SERVICE_AREAS = Set.of(
        "Jubilee Hills, Hyderabad",
        "Banjara Hills, Hyderabad",
        "Gachibowli, Hyderabad"
    );

    private final JdbcTemplate db;
    private final CatalogService catalog;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();
    private final boolean demo;

    public DefaultOrderService(JdbcTemplate db,
                               CatalogService catalog,
                               AuditService audit,
                               AppProperties appProperties) {
        this.db = db;
        this.catalog = catalog;
        this.audit = audit;
        this.demo = appProperties.isDemoOperations();
    }

    /* ---------------- validation helpers ---------------- */

    private boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    private void require(boolean condition, int status, String message) {
        if (!condition) throw new DomainException(status, message);
    }

    private String text(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }

    private String restaurantIdOf(Authentication auth) {
        return db.queryForObject(
            "SELECT restaurant_id FROM app_users WHERE username=?",
            String.class, auth.getName()
        );
    }

    /* ---------------- place ---------------- */

    @Override
    @Transactional
    public OrderView place(Authentication auth, String idempotencyKey, Checkout request) {
        require(hasRole(auth, "CUSTOMER"), 403, "Only customers can order");
        require(idempotencyKey != null && idempotencyKey.matches("[A-Za-z0-9_-]{8,100}"),
            400, "Supply an Idempotency-Key of 8–100 letters, digits, underscores or hyphens");
        require(SERVICE_AREAS.contains(request.area()), 422, "This area is outside the pilot service zone");

        // Row lock serialises retries across application instances for this customer
        db.queryForObject(
            "SELECT username FROM app_users WHERE username=? FOR UPDATE",
            String.class, auth.getName()
        );

        List<Line> sorted = request.items().stream()
            .sorted(Comparator.comparing(Line::dishId))
            .toList();

        require(sorted.stream().map(Line::dishId).distinct().count() == sorted.size(),
            422, "Combine duplicate dishes into one line");

        String fingerprint;
        try {
            fingerprint = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    json.writeValueAsBytes(new Checkout(
                        request.restaurantId(),
                        request.address().trim(),
                        request.area(),
                        sorted
                    ))
                )
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        List<Map<String, Object>> existing = db.queryForList(
            "SELECT id, request_hash FROM orders WHERE customer=? AND idempotency_key=?",
            auth.getName(), idempotencyKey
        );

        if (!existing.isEmpty()) {
            require(fingerprint.equals(existing.getFirst().get("request_hash")),
                409, "Idempotency key was used for different input");
            return view((String) existing.getFirst().get("id"));
        }

        catalog.restaurant(request.restaurantId());

        List<OrderLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (var line : sorted) {
            List<Map<String, Object>> dish = db.queryForList(
                "SELECT * FROM dishes WHERE id=? AND restaurant_id=? AND available=TRUE",
                line.dishId(), request.restaurantId()
            );
            require(!dish.isEmpty(), 422, "Dish unavailable or belongs to another restaurant: " + line.dishId());
            var d = dish.getFirst();
            var price = (BigDecimal) d.get("price");
            subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(line.quantity())));
            lines.add(new OrderLine(
                line.dishId(),
                d.get("name").toString(),
                text(price),
                line.quantity()
            ));
        }

        String id = UUID.randomUUID().toString();
        BigDecimal fee = new BigDecimal("35.00");
        String now = Instant.now().toString();
        db.update(
            "INSERT INTO orders(id,customer,restaurant_id,status,payment_status,refund_status," +
            "subtotal,delivery_fee,total,address,area,created_at,idempotency_key,request_hash) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, auth.getName(), request.restaurantId(), "PLACED", "DEMO_PAID", "NONE",
            subtotal, fee, subtotal.add(fee), request.address().trim(), request.area(),
            now, idempotencyKey, fingerprint
        );

        for (var line : lines) {
            db.update("INSERT INTO order_items VALUES(?,?,?,?,?)",
                id, line.dishId(), line.name(), new BigDecimal(line.unitPrice()), line.quantity());
        }

        audit.record(id, auth.getName(), "OrderPlaced", "Demo payment; no funds collected");
        return view(id);
    }

    /* ---------------- read ---------------- */

    @Override
    @Transactional(readOnly = true)
    public OrderView get(Authentication auth, String id) {
        var order = view(id);
        require(access(auth, order), 404, "Order not found");
        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderView> list(Authentication auth) {
        String where;
        Object[] values;
        if (hasRole(auth, "ADMIN")) {
            where = "";
            values = new Object[0];
        } else if (hasRole(auth, "RESTAURANT")) {
            where = " WHERE restaurant_id=?";
            values = new Object[]{restaurantIdOf(auth)};
        } else if (hasRole(auth, "PARTNER")) {
            where = " WHERE partner=?";
            values = new Object[]{auth.getName()};
        } else {
            where = " WHERE customer=?";
            values = new Object[]{auth.getName()};
        }
        return db.queryForList(
            "SELECT id FROM orders" + where + " ORDER BY created_at DESC LIMIT 100",
            String.class, values
        ).stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(Authentication auth, String id) {
        get(auth, id); // enforces access
        return db.queryForList(
            "SELECT actor,action,occurred_at,note FROM order_actions WHERE order_id=? ORDER BY occurred_at",
            id
        );
    }

    /* ---------------- transitions ---------------- */

    @Override
    @Transactional
    public OrderView transition(Authentication auth, String id, Action request) {
        lock(id);
        var o = get(auth, id);
        require(o.version() == request.expectedVersion(), 409, "Order changed; reload before acting");
        String next = request.status();
        boolean allowed = false;

        if (hasRole(auth, "CUSTOMER")) {
            allowed = next.equals("CANCELLED") && o.status().equals("PLACED");
        }
        if (hasRole(auth, "ADMIN") || hasRole(auth, "RESTAURANT")) {
            allowed = switch (o.status()) {
                case "PLACED" -> Set.of("ACCEPTED", "REJECTED").contains(next);
                case "ACCEPTED" -> next.equals("PREPARING");
                case "PREPARING" -> next.equals("READY_FOR_PICKUP");
                default -> false;
            };
        }
        if (hasRole(auth, "PARTNER") && auth.getName().equals(o.partner())) {
            allowed = (o.status().equals("READY_FOR_PICKUP") && next.equals("PICKED_UP"))
                || (o.status().equals("PICKED_UP") && next.equals("DELIVERED"));
        }
        require(allowed, 409, "Action is not permitted for this role and order state");

        String refund = Set.of("CANCELLED", "REJECTED").contains(next)
            ? "DEMO_REFUNDED"
            : o.refundStatus();
        db.update(
            "UPDATE orders SET status=?,refund_status=?,version=version+1 WHERE id=?",
            next, refund, id
        );
        audit.record(id, auth.getName(), "Order" + next, request.note());
        return view(id);
    }

    @Override
    @Transactional
    public OrderView assign(Authentication auth, String id, Assign request) {
        require(hasRole(auth, "ADMIN"), 403, "Only operations can assign partners");
        lock(id);
        var o = view(id);
        require(o.version() == request.expectedVersion(), 409, "Order changed; reload before assigning");
        require(Set.of("ACCEPTED", "PREPARING", "READY_FOR_PICKUP").contains(o.status()),
            409, "Order is not ready for assignment");
        require(o.partner() == null, 409, "Already assigned");
        require(db.queryForObject(
            "SELECT COUNT(*) FROM app_users WHERE username=? AND role='PARTNER'",
            Integer.class, request.partner()) == 1, 422, "Unknown delivery partner");
        db.queryForObject(
            "SELECT username FROM app_users WHERE username=? FOR UPDATE",
            String.class, request.partner()
        );
        require(db.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE partner=? AND status NOT IN ('DELIVERED','CANCELLED','REJECTED')",
            Integer.class, request.partner()) == 0, 409, "Partner has an active delivery");
        db.update("UPDATE orders SET partner=?,version=version+1 WHERE id=?", request.partner(), id);
        audit.record(id, auth.getName(), "PartnerAssigned", request.partner());
        return view(id);
    }

    @Override
    @Transactional
    public OrderView advanceDemo(Authentication auth, String id, Action request) {
        require(demo && hasRole(auth, "ADMIN"), 403, "Demo advancement is restricted to demo administrators");
        lock(id);
        var o = view(id);
        require(o.version() == request.expectedVersion(), 409, "Order changed; reload");
        String next = switch (o.status()) {
            case "PLACED" -> "ACCEPTED";
            case "ACCEPTED" -> "PREPARING";
            case "PREPARING" -> "READY_FOR_PICKUP";
            case "READY_FOR_PICKUP" -> "PICKED_UP";
            case "PICKED_UP" -> "DELIVERED";
            default -> throw new DomainException(409, "Order is terminal");
        };
        db.update("UPDATE orders SET status=?,version=version+1 WHERE id=?", next, id);
        audit.record(id, auth.getName(), "DemoAdvanced", request.note());
        return view(id);
    }

    /* ---------------- internal helpers ---------------- */

    @Transactional(readOnly = true)
    OrderView view(String id) {
        return db.query(
            "SELECT o.*, r.content FROM orders o JOIN restaurants r ON o.restaurant_id=r.id WHERE o.id=?",
            (rs, n) -> {
                String name;
                try {
                    name = json.readTree(rs.getString("content")).get("name").asText();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return new OrderView(
                    rs.getString("id"),
                    name,
                    rs.getString("restaurant_id"),
                    rs.getString("customer"),
                    rs.getString("partner"),
                    rs.getString("status"),
                    rs.getString("payment_status"),
                    rs.getString("refund_status"),
                    text(rs.getBigDecimal("subtotal")),
                    text(rs.getBigDecimal("delivery_fee")),
                    text(rs.getBigDecimal("total")),
                    rs.getString("address"),
                    rs.getString("area"),
                    rs.getString("created_at"),
                    rs.getLong("version"),
                    db.query(
                        "SELECT * FROM order_items WHERE order_id=? ORDER BY dish_id",
                        (r, i) -> new OrderLine(
                            r.getString("dish_id"),
                            r.getString("name"),
                            text(r.getBigDecimal("unit_price")),
                            r.getInt("quantity")
                        ),
                        id
                    )
                );
            },
            id
        ).stream().findFirst()
        .orElseThrow(() -> new DomainException(404, "Order not found"));
    }

    private boolean access(Authentication auth, OrderView o) {
        return hasRole(auth, "ADMIN")
            || o.customer().equals(auth.getName())
            || (hasRole(auth, "RESTAURANT") && o.restaurantId().equals(restaurantIdOf(auth)))
            || (hasRole(auth, "PARTNER") && auth.getName().equals(o.partner()));
    }

    private void lock(String id) {
        require(!db.queryForList(
            "SELECT id FROM orders WHERE id=? FOR UPDATE",
            String.class, id
        ).isEmpty(), 404, "Order not found");
    }
}
