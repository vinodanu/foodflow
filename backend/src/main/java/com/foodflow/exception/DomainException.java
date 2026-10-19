package com.foodflow.exception;

/**
 * Business-domain exception carrying an HTTP status code.
 * Thrown by services; translated to a ProblemDetail response by {@link com.foodflow.exception.GlobalExceptionHandler}.
 */
public class DomainException extends RuntimeException {

    private final int status;

    public DomainException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
