package com.sinx.platform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void reportsAnUnmatchedPathAsNotFoundRatherThanAServerError() {
        ProblemDetail problem = handler.handleUnknownRoute(
            new NoResourceFoundException(
                HttpMethod.GET,
                "api/v2/admin/stat/getStats",
                "/api/v2/admin/stat/getStats"
            ),
            new MockHttpServletRequest("GET", "/api/v2/admin/stat/getStats")
        );

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getProperties()).containsEntry("code", "NOT_FOUND");
    }

    @Test
    void keepsTheProblemTypeFreeOfAnyHostname() {
        ProblemDetail problem = handler.handleUnknownRoute(
            new NoResourceFoundException(HttpMethod.GET, "x", "/x"),
            new MockHttpServletRequest("GET", "/x")
        );

        // An absolute URI here shipped whichever host was hardcoded into every
        // environment's error responses.
        URI type = problem.getType();
        assertThat(type.isAbsolute()).isFalse();
        assertThat(type.getHost()).isNull();
        assertThat(type.getPath()).isEqualTo("/problems/not_found");
    }
}
