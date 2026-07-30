package com.sinx.platform.identity.web;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.identity.application.AdminMfaService;
import com.sinx.platform.identity.application.AdminMfaService.MfaStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/admin-session/mfa")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('SCOPE_ADMIN')")
public class AdminMfaController {

    private final AdminMfaService mfaService;

    public AdminMfaController(AdminMfaService mfaService) {
        this.mfaService = mfaService;
    }

    @GetMapping
    MfaStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        MfaStatus status = mfaService.status(userId(jwt));
        return new MfaStatusResponse(status.enabled(), status.enabledAt());
    }

    @DeleteMapping
    ResponseEntity<Void> disable(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody DisableMfaRequest request
    ) {
        mfaService.disable(
            userId(jwt),
            request.password(),
            request.code()
        );
        return ResponseEntity.noContent().build();
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record MfaStatusResponse(boolean enabled, Instant enabledAt) {
    }

    public record DisableMfaRequest(
        @NotBlank @Size(max = 128) String password,
        @NotBlank @Size(min = 6, max = 32) String code
    ) {
    }
}
