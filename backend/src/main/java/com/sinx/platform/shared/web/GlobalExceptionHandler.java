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
        problem.setType(URI.create("https://dev.sinx.it.com/problems/" + code.toLowerCase()));
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
