package com.foodflow.order;

import com.foodflow.domain.model.Action;
import com.foodflow.domain.model.Assign;
import com.foodflow.domain.model.Checkout;
import com.foodflow.domain.model.OrderView;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * HTTP layer for orders. Translates requests to {@link OrderService} calls; contains no business logic.
 * SRP: only request/response mapping and HTTP concerns live here.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public OrderView place(Authentication auth,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                           @Valid @RequestBody Checkout input) {
        return orders.place(auth, key, input);
    }

    @GetMapping
    public List<OrderView> list(Authentication auth) {
        return orders.list(auth);
    }

    @GetMapping("/{id}")
    public OrderView get(Authentication auth, @PathVariable String id) {
        return orders.get(auth, id);
    }

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(Authentication auth, @PathVariable String id) {
        return orders.history(auth, id);
    }

    @PostMapping("/{id}/status")
    public OrderView status(Authentication auth,
                            @PathVariable String id,
                            @Valid @RequestBody Action request) {
        return orders.transition(auth, id, request);
    }

    @PostMapping("/{id}/assign")
    public OrderView assign(Authentication auth,
                            @PathVariable String id,
                            @Valid @RequestBody Assign request) {
        return orders.assign(auth, id, request);
    }

    @PostMapping("/{id}/demo-advance")
    public OrderView advance(Authentication auth,
                             @PathVariable String id,
                             @Valid @RequestBody Action request) {
        return orders.advanceDemo(auth, id, request);
    }
}
