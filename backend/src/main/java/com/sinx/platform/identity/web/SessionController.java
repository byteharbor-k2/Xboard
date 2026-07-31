package com.sinx.platform.identity.web;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.identity.application.IdentityService;
import com.sinx.platform.identity.application.RegistrationVerificationService;
import com.sinx.platform.identity.application.ScopedSessionService.SessionGrant;
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
    private final RegistrationVerificationService registrationVerification;
    private final Clock clock;

    public SessionController(
        IdentityService identityService,
        SessionCookieService cookieService,
        RegistrationVerificationService registrationVerification,
        Clock clock
    ) {
        this.identityService = identityService;
        this.cookieService = cookieService;
        this.registrationVerification = registrationVerification;
        this.clock = clock;
    }

    @PostMapping("/register")
    ResponseEntity<SessionResponse> register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse response
    ) {
        SessionGrant grant = identityService.register(
            request.email(),
            request.password(),
            request.displayName(),
            request.deviceLabel(),
            request.emailCode(),
            request.turnstileToken(),
            request.inviteCode(),
            servletRequest.getRemoteAddr()
        );
        writeRefreshCookie(response, grant);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SessionResponse.from(grant));
    }

    @GetMapping("/registration/config")
    RegistrationVerificationService.RegistrationConfig registrationConfig() {
        return registrationVerification.config();
    }

    @PostMapping("/registration/email-code")
    ResponseEntity<Void> requestRegistrationCode(
        @Valid @RequestBody RegistrationCodeRequest request,
        HttpServletRequest servletRequest
    ) {
        registrationVerification.requestCode(
            request.email(),
            request.turnstileToken(),
            servletRequest.getRemoteAddr()
        );
        return ResponseEntity.accepted().build();
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
        String refreshToken = cookieService.readUser(request)
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
        cookieService.readUser(request).ifPresent(identityService::logout);
        cookieService.clearUser(response);
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

    @PostMapping("/password-reset/request")
    ResponseEntity<Void> requestPasswordReset(
        @Valid @RequestBody PasswordResetRequest request
    ) {
        identityService.requestPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(
        @Valid @RequestBody ConfirmPasswordResetRequest request
    ) {
        identityService.confirmPasswordReset(
            request.token(),
            request.newPassword()
        );
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/password")
    ResponseEntity<Void> changePassword(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ChangePasswordRequest request
    ) {
        identityService.changePassword(
            UUID.fromString(jwt.getSubject()),
            UUID.fromString(jwt.getClaimAsString("sid")),
            request.currentPassword(),
            request.newPassword()
        );
        return ResponseEntity.noContent().build();
    }

    private void writeRefreshCookie(
        HttpServletResponse response,
        SessionGrant grant
    ) {
        cookieService.writeUser(
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
        @Size(max = 120) String deviceLabel,
        @Size(min = 6, max = 6) String emailCode,
        @Size(max = 2048) String turnstileToken,
        @Size(max = 32) String inviteCode
    ) {
    }

    public record RegistrationCodeRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @Size(max = 2048) String turnstileToken
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

    public record PasswordResetRequest(
        @NotBlank @Email @Size(max = 320) String email
    ) {
    }

    public record ConfirmPasswordResetRequest(
        @NotBlank @Size(min = 32, max = 256) String token,
        @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {
    }

    public record ChangePasswordRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(min = 12, max = 128) String newPassword
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
