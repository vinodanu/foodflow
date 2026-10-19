package com.foodflow.domain.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Checkout request payload. Carries only data; no behaviour.
 */
public record Checkout(
    @NotBlank String restaurantId,
    @NotBlank @Size(min = 10, max = 500) String address,
    @NotBlank String area,
    @NotEmpty @Size(max = 30) List<@Valid Line> items
) {}
