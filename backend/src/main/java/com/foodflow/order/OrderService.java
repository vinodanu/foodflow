package com.foodflow.order;

import com.foodflow.domain.model.Action;
import com.foodflow.domain.model.Assign;
import com.foodflow.domain.model.Checkout;
import com.foodflow.domain.model.OrderView;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

/**
 * Ordering domain contract. Implementations decide persistence, locking, and audit details;
 * controllers and tests depend only on this interface (DIP).
 */
public interface OrderService {

    OrderView place(Authentication auth, String idempotencyKey, Checkout request);

    OrderView get(Authentication auth, String id);

    List<OrderView> list(Authentication auth);

    OrderView transition(Authentication auth, String id, Action request);

    OrderView assign(Authentication auth, String id, Assign request);

    OrderView advanceDemo(Authentication auth, String id, Action request);

    List<Map<String, Object>> history(Authentication auth, String id);
}
