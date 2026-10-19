package com.foodflow.domain.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Partner assignment request. Carries only data; no behaviour.
 */
public record Assign(
    @NotBlank String partner,
    @Min(0) long expectedVersion
) {}
