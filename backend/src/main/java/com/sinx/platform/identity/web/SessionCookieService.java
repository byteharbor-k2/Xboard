package com.sinx.platform.identity.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.sinx.platform.identity.security.IdentitySecurityProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
class SessionCookieService {

    private final IdentitySecurityProperties properties;

    SessionCookieService(IdentitySecurityProperties properties) {
        this.properties = properties;
    }

    Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
            .filter(cookie -> properties.refreshCookieName().equals(cookie.getName()))
            .map(Cookie::getValue)
            .findFirst();
    }

    void write(
        HttpServletResponse response,
        String refreshToken,
        Instant expiresAt,
        Instant now
    ) {
        ResponseCookie cookie = baseCookie(refreshToken)
            .maxAge(Duration.between(now, expiresAt))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    void clear(HttpServletResponse response) {
        ResponseCookie cookie = baseCookie("")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(properties.refreshCookieName(), value)
            .httpOnly(true)
            .secure(properties.secureCookies())
            .sameSite("Strict")
            .path("/session");
    }
}
