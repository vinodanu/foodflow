package com.foodflow.security;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request payload. Carries only data; no behaviour.
 */
public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password
) {}
