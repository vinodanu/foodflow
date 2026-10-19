package com.foodflow.domain.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * A single dish line in a checkout request.
 */
public record Line(
    @NotBlank String dishId,
    @Min(1) @Max(20) int quantity
) {}
