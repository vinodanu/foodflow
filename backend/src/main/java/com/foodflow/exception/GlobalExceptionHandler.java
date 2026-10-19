package com.foodflow.exception;

import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Single translation layer from domain/validation exceptions to RFC 7807 ProblemDetail responses.
 * SRP: controllers and services never deal with HTTP status mapping.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException ex) {
        return ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatusCode.valueOf(ex.getStatus()),
            ex.getMessage()
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail auth(AuthenticationException ex) {
        return ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatusCode.valueOf(401),
            "Invalid username or password"
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException ex) {
        var p = ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatusCode.valueOf(400),
            "Check the submitted fields"
        );
        p.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .toList());
        return p;
    }
}
