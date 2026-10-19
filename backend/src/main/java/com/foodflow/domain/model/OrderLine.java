package com.foodflow.domain.model;

/**
 * A single line inside an order view (read model).
 */
public record OrderLine(
    String dishId,
    String name,
    String unitPrice,
    int quantity
) {}
