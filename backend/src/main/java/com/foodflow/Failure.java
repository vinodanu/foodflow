package com.foodflow;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

class Failure extends RuntimeException {final int status;Failure(int status,String message){super(message);this.status=status;}}
@RestControllerAdvice
class Errors {
 @ExceptionHandler(Failure.class) ProblemDetail domain(Failure f){return ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(f.status),f.getMessage());}
 @ExceptionHandler(AuthenticationException.class) ProblemDetail auth(){return ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(401),"Invalid username or password");}
 @ExceptionHandler(MethodArgumentNotValidException.class) ProblemDetail validation(MethodArgumentNotValidException e){var p=ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(400),"Check the submitted fields");p.setProperty("errors",e.getBindingResult().getFieldErrors().stream().map(f->f.getField()+": "+f.getDefaultMessage()).toList());return p;}
}
