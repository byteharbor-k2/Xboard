package com.sinx.platform.audit.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import com.sinx.platform.audit.application.AdminAuditService;
import com.sinx.platform.audit.application.AdminAuditService.AuditRecord;
import com.sinx.platform.shared.web.RequestIdFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class AdminAuditFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        AdminAuditFilter.class
    );
    private static final Pattern OPERATION = Pattern.compile(
        "\\b(?:query|mutation)\\s+([_A-Za-z][_0-9A-Za-z]*)"
    );

    private final AdminAuditService auditService;

    public AdminAuditFilter(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/gateway".equals(request.getRequestURI())
            || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped =
            new ContentCachingRequestWrapper(request, 16_384);
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();
            if (isAdmin(authentication)) {
                try {
                    auditService.record(
                        UUID.fromString(authentication.getName()),
                        new AuditRecord(
                            resolveAction(wrapped),
                            request.getMethod(),
                            request.getRequestURI(),
                            response.getStatus(),
                            (System.nanoTime() - startedAt) / 1_000_000,
                            (String) request.getAttribute(
                                RequestIdFilter.ATTRIBUTE_NAME
                            ),
                            request.getRemoteAddr(),
                            request.getHeader("User-Agent")
                        )
                    );
                } catch (RuntimeException exception) {
                    LOGGER.warn(
                        "Administrative audit write failed",
                        exception
                    );
                }
            }
        }
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_ADMIN"::equals);
    }

    private String resolveAction(ContentCachingRequestWrapper request) {
        byte[] body = request.getContentAsByteArray();
        if (body.length == 0) {
            return "graphql.anonymous";
        }
        String payload = new String(body, StandardCharsets.UTF_8);
        Matcher matcher = OPERATION.matcher(payload);
        if (matcher.find() && StringUtils.hasText(matcher.group(1))) {
            return "graphql." + matcher.group(1);
        }
        return "graphql.anonymous";
    }
}
