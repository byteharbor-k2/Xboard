package com.sinx.platform.identity.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.identity.application.AdminMfaService.EnrollmentComplete;
import com.sinx.platform.identity.application.AdminMfaService.EnrollmentStart;
import com.sinx.platform.identity.application.AdminSessionService;
import com.sinx.platform.identity.application.AdminSessionService.LoginResult;
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
@RequestMapping("/admin-session")
public class AdminSessionController {

    private final AdminSessionService adminSessions;
    private final SessionCookieService cookies;
    private final Clock clock;

    public AdminSessionController(
        AdminSessionService adminSessions,
        SessionCookieService cookies,
        Clock clock
    ) {
        this.adminSessions = adminSessions;
        this.cookies = cookies;
        this.clock = clock;
    }

    @PostMapping("/login")
    ResponseEntity<AdminLoginResponse> login(
        @Valid @RequestBody LoginRequest request
    ) {
        LoginResult result = adminSessions.login(
            request.email(),
            request.password(),
            request.deviceLabel()
        );
        if (result.requiresMfa()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                AdminLoginResponse.mfa(result.mfaChallenge())
            );
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            AdminLoginResponse.enrollment(result.enrollmentChallenge())
        );
    }

    @PostMapping("/login/mfa")
    SessionResponse completeMfaLogin(
        @Valid @RequestBody CompleteMfaLoginRequest request,
        HttpServletResponse response
    ) {
        SessionGrant grant = adminSessions.completeMfaLogin(
            request.challengeToken(),
            request.code()
        );
        writeRefreshCookie(response, grant);
        return SessionResponse.from(grant);
    }

    @PostMapping("/enrollment")
    EnrollmentStartResponse startEnrollment(
        @Valid @RequestBody EnrollmentRequest request
    ) {
        EnrollmentStart enrollment = adminSessions.startEnrollment(
            request.enrollmentToken()
        );
        return new EnrollmentStartResponse(
            enrollment.secret(),
            enrollment.otpauthUri()
        );
    }

    @PostMapping("/enrollment/confirm")
    EnrollmentCompleteResponse confirmEnrollment(
        @Valid @RequestBody ConfirmEnrollmentRequest request
    ) {
        EnrollmentComplete enrollment = adminSessions.confirmEnrollment(
            request.enrollmentToken(),
            request.code()
        );
        return new EnrollmentCompleteResponse(enrollment.recoveryCodes());
    }

    @PostMapping("/refresh")
    SessionResponse refresh(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String refreshToken = cookies.readAdmin(request)
            .orElseThrow(this::missingRefreshToken);
        SessionGrant grant = adminSessions.refresh(refreshToken);
        writeRefreshCookie(response, grant);
        return SessionResponse.from(grant);
    }

    @DeleteMapping("/current")
    ResponseEntity<Void> logout(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        cookies.readAdmin(request).ifPresent(adminSessions::logout);
        cookies.clearAdmin(response);
        return ResponseEntity.noContent().build();
    }

    private void writeRefreshCookie(
        HttpServletResponse response,
        SessionGrant grant
    ) {
        cookies.writeAdmin(
            response,
            grant.refreshToken(),
            grant.refreshTokenExpiresAt(),
            Instant.now(clock)
        );
    }

    private ApiProblemException missingRefreshToken() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "ADMIN_REFRESH_COOKIE_MISSING",
            "No administrator refresh session was provided"
        );
    }

    public record LoginRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 128) String password,
        @Size(max = 120) String deviceLabel
    ) {
    }

    public record CompleteMfaLoginRequest(
        @NotBlank @Size(min = 32, max = 256) String challengeToken,
        @NotBlank @Size(min = 6, max = 32) String code
    ) {
    }

    public record EnrollmentRequest(
        @NotBlank @Size(min = 32, max = 256) String enrollmentToken
    ) {
    }

    public record ConfirmEnrollmentRequest(
        @NotBlank @Size(min = 32, max = 256) String enrollmentToken,
        @NotBlank @Size(min = 6, max = 6) String code
    ) {
    }

    public record AdminLoginResponse(
        boolean mfaRequired,
        boolean mfaEnrollmentRequired,
        String challengeToken,
        String enrollmentToken,
        Instant expiresAt
    ) {
        static AdminLoginResponse mfa(
            com.sinx.platform.identity.application.MfaChallengeService.IssuedChallenge challenge
        ) {
            return new AdminLoginResponse(
                true,
                false,
                challenge.token(),
                null,
                challenge.expiresAt()
            );
        }

        static AdminLoginResponse enrollment(
            com.sinx.platform.identity.application.MfaChallengeService.IssuedChallenge challenge
        ) {
            return new AdminLoginResponse(
                false,
                true,
                null,
                challenge.token(),
                challenge.expiresAt()
            );
        }
    }

    public record EnrollmentStartResponse(
        String secret,
        String otpauthUri
    ) {
    }

    public record EnrollmentCompleteResponse(List<String> recoveryCodes) {
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
