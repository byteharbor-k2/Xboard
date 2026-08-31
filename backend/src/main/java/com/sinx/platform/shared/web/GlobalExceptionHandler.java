package com.sinx.platform.shared.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiProblemException.class)
    ProblemDetail handleApiProblem(
        ApiProblemException exception,
        HttpServletRequest request
    ) {
        return baseProblem(
            exception.getStatus(),
            exception.getCode(),
            exception.getMessage(),
            request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        ProblemDetail problem = baseProblem(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "VALIDATION_FAILED",
            "Request validation failed",
            request
        );

        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
            errors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * An unmatched path reaches the static-resource handler, which raises this.
     * Left to the catch-all it became a 500 with a full stack trace, so every
     * request to a not-yet-implemented endpoint both lied about the cause and
     * buried real failures in the log.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleUnknownRoute(
        NoResourceFoundException exception,
        HttpServletRequest request
    ) {
        LOGGER.debug(
            "No handler for {} {}",
            request.getMethod(),
            request.getRequestURI()
        );
        return baseProblem(
            HttpStatus.NOT_FOUND,
            "NOT_FOUND",
            "The requested endpoint does not exist",
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled request failure for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return baseProblem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "The request could not be completed",
            request
        );
    }

    private ProblemDetail baseProblem(
        HttpStatus status,
        String code,
        String detail,
        HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        // Relative on purpose: an absolute URI here pinned every environment to
        // whichever host was hardcoded, leaking it into production responses.
        problem.setType(URI.create("/problems/" + code.toLowerCase()));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty(
            "traceId",
            request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME)
        );
        return problem;
    }
}
