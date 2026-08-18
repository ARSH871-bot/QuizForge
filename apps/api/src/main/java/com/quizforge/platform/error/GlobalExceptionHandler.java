package com.quizforge.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

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

    /**
     * Constraint violations on method parameters.
     *
     * <p>These arrive from the constraints openapi-generator writes onto the
     * generated interfaces - {@code @Pattern} on an identifier, {@code @Min} and
     * {@code @Max} on a page size. The contract validates the request before any
     * controller body runs, which is the point of generating from it.
     *
     * <p>Without this handler those violations fell through to the catch-all and
     * were reported as 500. A caller sending a bad page size was told the server
     * had failed, when in fact the server had correctly rejected them.
     */
    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ProblemDetail handleParameterValidation(Exception e, HttpServletRequest request) {
        String detail = switch (e) {
            case HandlerMethodValidationException methodValidation -> methodValidation
                    .getAllValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(error -> describe(result.getMethodParameter().getParameterName(),
                                    error.getDefaultMessage())))
                    .collect(Collectors.joining("; "));
            case ConstraintViolationException violations -> violations.getConstraintViolations()
                    .stream()
                    .map(v -> describe(v.getPropertyPath().toString(), v.getMessage()))
                    .collect(Collectors.joining("; "));
            default -> "the request failed validation";
        };

        return problem(ErrorCode.INVALID_REQUEST,
                detail.isBlank() ? "the request failed validation" : detail, request);
    }

    private static String describe(String parameter, String message) {
        return (parameter == null ? "request" : parameter) + ": " + message;
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
