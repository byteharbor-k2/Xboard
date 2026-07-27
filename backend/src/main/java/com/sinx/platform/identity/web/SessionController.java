package com.sinx.platform.identity.web;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.identity.application.IdentityService;
import com.sinx.platform.identity.application.IdentityService.SessionGrant;
import com.sinx.platform.identity.application.ViewerView;
import com.sinx.platform.shared.web.ApiProblemException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/session")
public class SessionController {

    private final IdentityService identityService;
    private final SessionCookieService cookieService;
    private final Clock clock;

    public SessionController(
        IdentityService identityService,
        SessionCookieService cookieService,
        Clock clock
    ) {
        this.identityService = identityService;
        this.cookieService = cookieService;
        this.clock = clock;
    }

    @PostMapping("/register")
    ResponseEntity<SessionResponse> register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletResponse response
    ) {
        SessionGrant grant = identityService.register(
            request.email(),
            request.password(),
            request.displayName(),
            request.deviceLabel()
        );
        writeRefreshCookie(response, grant);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SessionResponse.from(grant));
    }

    @PostMapping("/login")
    SessionResponse login(
        @Valid @RequestBody LoginRequest request,
        HttpServletResponse response
    ) {
        SessionGrant grant = identityService.login(
            request.email(),
            request.password(),
            request.deviceLabel()
        );
        writeRefreshCookie(response, grant);
        return SessionResponse.from(grant);
    }

    @PostMapping("/refresh")
    SessionResponse refresh(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String refreshToken = cookieService.read(request)
            .orElseThrow(this::missingRefreshToken);
        SessionGrant grant = identityService.refresh(refreshToken);
        writeRefreshCookie(response, grant);
        return SessionResponse.from(grant);
    }

    @DeleteMapping("/current")
    ResponseEntity<Void> logout(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        cookieService.read(request).ifPresent(identityService::logout);
        cookieService.clear(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email-verification/request")
    ResponseEntity<Void> requestEmailVerification(
        @AuthenticationPrincipal Jwt jwt
    ) {
        identityService.requestEmailVerification(
            UUID.fromString(jwt.getSubject())
        );
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email-verification/confirm")
    ResponseEntity<Void> confirmEmailVerification(
        @Valid @RequestBody ConfirmEmailRequest request
    ) {
        identityService.confirmEmailVerification(request.token());
        return ResponseEntity.noContent().build();
    }

    private void writeRefreshCookie(
        HttpServletResponse response,
        SessionGrant grant
    ) {
        cookieService.write(
            response,
            grant.refreshToken(),
            grant.refreshTokenExpiresAt(),
            Instant.now(clock)
        );
    }

    private ApiProblemException missingRefreshToken() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "REFRESH_COOKIE_MISSING",
            "No refresh session was provided"
        );
    }

    public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 12, max = 128) String password,
        @NotBlank @Size(max = 80) String displayName,
        @Size(max = 120) String deviceLabel
    ) {
    }

    public record LoginRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 128) String password,
        @Size(max = 120) String deviceLabel
    ) {
    }

    public record ConfirmEmailRequest(
        @NotBlank @Size(min = 32, max = 256) String token
    ) {
    }

    public record SessionResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        UUID sessionId,
        ViewerView viewer
    ) {
        static SessionResponse from(SessionGrant grant) {
            return new SessionResponse(
                grant.accessToken(),
                grant.accessTokenExpiresAt(),
                grant.sessionId(),
                grant.viewer()
            );
        }
    }
}
