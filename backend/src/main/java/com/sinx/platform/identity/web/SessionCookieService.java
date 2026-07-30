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

    Optional<String> readUser(HttpServletRequest request) {
        return read(request, properties.refreshCookieName());
    }

    Optional<String> readAdmin(HttpServletRequest request) {
        return read(request, properties.adminRefreshCookieName());
    }

    private Optional<String> read(
        HttpServletRequest request,
        String cookieName
    ) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
            .filter(cookie -> cookieName.equals(cookie.getName()))
            .map(Cookie::getValue)
            .findFirst();
    }

    void writeUser(
        HttpServletResponse response,
        String refreshToken,
        Instant expiresAt,
        Instant now
    ) {
        write(
            response,
            refreshToken,
            expiresAt,
            now,
            properties.refreshCookieName(),
            "/session"
        );
    }

    void writeAdmin(
        HttpServletResponse response,
        String refreshToken,
        Instant expiresAt,
        Instant now
    ) {
        write(
            response,
            refreshToken,
            expiresAt,
            now,
            properties.adminRefreshCookieName(),
            "/admin-session"
        );
    }

    private void write(
        HttpServletResponse response,
        String refreshToken,
        Instant expiresAt,
        Instant now,
        String cookieName,
        String path
    ) {
        ResponseCookie cookie = baseCookie(
            cookieName,
            refreshToken,
            path
        )
            .maxAge(Duration.between(now, expiresAt))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    void clearUser(HttpServletResponse response) {
        clear(
            response,
            properties.refreshCookieName(),
            "/session"
        );
    }

    void clearAdmin(HttpServletResponse response) {
        clear(
            response,
            properties.adminRefreshCookieName(),
            "/admin-session"
        );
    }

    private void clear(
        HttpServletResponse response,
        String cookieName,
        String path
    ) {
        ResponseCookie cookie = baseCookie(cookieName, "", path)
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(
        String cookieName,
        String value,
        String path
    ) {
        return ResponseCookie.from(cookieName, value)
            .httpOnly(true)
            .secure(properties.secureCookies())
            .sameSite("Strict")
            .path(path);
    }
}
