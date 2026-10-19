package com.foodflow.domain.model;

import java.util.List;

/**
 * Read-model returned to callers after order operations.
 * Carries only data; no behaviour.
 */
public record OrderView(
    String id,
    String restaurant,
    String restaurantId,
    String customer,
    String partner,
    String status,
    String paymentStatus,
    String refundStatus,
    String subtotal,
    String deliveryFee,
    String total,
    String address,
    String area,
    String createdAt,
    long version,
    List<OrderLine> items
) {}
