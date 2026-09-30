package com.quizforge.platform.error;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.URI;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Translates exceptions into RFC 9457 Problem Details. Every error response in
 * the application has the same shape, and every one carries a stable,
 * machine-readable {@code type} that clients can branch on.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A 429 raised in a request carries Retry-After, like the filter's own 429s. */
    @ExceptionHandler(RateLimitedException.class)
    public ProblemDetail handleRateLimited(RateLimitedException e, HttpServletRequest request,
                                           jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Retry-After", String.valueOf(e.retryAfterSeconds()));
        return problem(e.code(), e.getMessage(), request);
    }

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

    /**
     * A request body Jackson could not turn into the expected type.
     *
     * <p>An unknown enum value, a string where a number belongs, a truncated
     * document, or no body at all. Every one of these is the caller's mistake,
     * and without this handler they fell through to the catch-all and were
     * reported as 500 - the SDK's first unknown scoring policy was answered
     * with "an unexpected error occurred", which is both wrong and unactionable.
     *
     * <p>Jackson's own message names internal classes and quotes the input, so
     * it is never passed through. The field path and the permitted values are
     * both things the caller already knows about; nothing else is disclosed.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e,
                                              HttpServletRequest request) {
        return problem(ErrorCode.INVALID_REQUEST, describeBody(e), request);
    }

    private static String describeBody(HttpMessageNotReadableException e) {
        // Spring's own wording for an absent body; there is no Jackson cause.
        if (e.getMessage() != null && e.getMessage().contains("Required request body is missing")) {
            return "a request body is required";
        }

        if (!(e.getCause() instanceof JsonMappingException mapping)) {
            return "the request body is not valid JSON";
        }

        String field = path(mapping);
        if (field == null) {
            return "the request body is not valid JSON";
        }

        // An enum can say exactly what it would have accepted, which is the
        // difference between a caller fixing a typo and a caller guessing.
        Class<?> type = declaredType(mapping);
        if (type != null && type.isEnum()) {
            return field + ": must be one of " + Arrays.stream(type.getEnumConstants())
                    .map(String::valueOf).collect(Collectors.joining(", "));
        }
        return field + ": is not a valid " + simpleName(type);
    }

    /** The dotted path to the offending field, as the caller wrote it. */
    private static String path(JsonMappingException e) {
        String field = e.getPath().stream()
                .map(reference -> reference.getFieldName() == null
                        ? "[" + reference.getIndex() + "]"
                        : reference.getFieldName())
                .collect(Collectors.joining("."));
        return field.isBlank() ? null : field;
    }

    /**
     * The type the offending field was declared as.
     *
     * <p>{@link InvalidFormatException} carries it. A {@code @JsonCreator} that
     * threw does not, so it is recovered from the field on the enclosing class
     * - which is how an unknown enum value gets told what the alternatives are.
     */
    private static Class<?> declaredType(JsonMappingException e) {
        if (e instanceof InvalidFormatException invalid && invalid.getTargetType() != null) {
            return invalid.getTargetType();
        }
        var path = e.getPath();
        if (path.isEmpty()) {
            return null;
        }
        var last = path.get(path.size() - 1);
        Object owner = last.getFrom();
        if (owner == null || last.getFieldName() == null) {
            return null;
        }
        Class<?> declaring = owner instanceof Class<?> type ? type : owner.getClass();
        try {
            return declaring.getDeclaredField(last.getFieldName()).getType();
        } catch (NoSuchFieldException | RuntimeException unknown) {
            return null;
        }
    }

    /**
     * What to call a type in a message to a caller.
     *
     * <p>Named the way the contract names it, not the way Java does: a client
     * sending a bad timestamp is told to send a date-time, not an
     * {@code offsetdatetime} it has never heard of.
     */
    private static String simpleName(Class<?> type) {
        if (type == null) {
            return "value";
        }
        return switch (type.getSimpleName()) {
            case "OffsetDateTime", "Instant", "LocalDateTime" -> "date-time (RFC 3339)";
            case "LocalDate" -> "date";
            case "Integer", "int", "Long", "long" -> "integer";
            case "Double", "double", "Float", "float", "BigDecimal" -> "number";
            case "Boolean", "boolean" -> "boolean";
            case "String" -> "string";
            case "UUID" -> "identifier";
            default -> type.getSimpleName().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /**
     * A path that matches nothing.
     *
     * <p>Until this existed, every unknown path reached the catch-all below and
     * was reported as a 500 - a typo in a client's URL looked like the server
     * falling over. Both exceptions mean the same thing: no route, no file.
     */
    @ExceptionHandler({org.springframework.web.servlet.NoHandlerFoundException.class,
            org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ProblemDetail handleNoRoute(Exception e, HttpServletRequest request) {
        return problem(ErrorCode.NOT_FOUND, "nothing exists at this address", request);
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
