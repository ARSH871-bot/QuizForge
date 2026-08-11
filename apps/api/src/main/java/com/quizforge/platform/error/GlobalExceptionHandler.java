package com.quizforge.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Translates exceptions into RFC 9457 Problem Details. Every error response in
 * the application has the same shape, and every one carries a stable,
 * machine-readable {@code type} that clients can branch on.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException e, HttpServletRequest request) {
        return problem(e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e,
                                          HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(ErrorCode.INVALID_REQUEST, detail, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        // Log the cause; never leak it to the caller.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(ErrorCode.INTERNAL, "An unexpected error occurred", request);
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.name());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        return problem;
    }
}
