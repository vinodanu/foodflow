package com.foodflow.domain.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Order status transition request. Carries only data; no behaviour.
 */
public record Action(
    @NotBlank String status,
    @Min(0) long expectedVersion,
    @NotBlank @Size(max = 500) String note
) {}
