package com.sinx.platform.identity.graphql;

import java.util.UUID;
import java.util.List;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import com.sinx.platform.identity.application.IdentityService;
import com.sinx.platform.identity.application.DeviceSessionView;
import com.sinx.platform.identity.application.ViewerView;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Controller
@Validated
public class ViewerController {

    private final IdentityService identityService;

    public ViewerController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    ViewerView viewer(@AuthenticationPrincipal Jwt jwt) {
        return identityService.viewer(UUID.fromString(jwt.getSubject()));
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    List<DeviceSessionView> deviceSessions(
        @AuthenticationPrincipal Jwt jwt
    ) {
        return identityService.deviceSessions(
            UUID.fromString(jwt.getSubject()),
            UUID.fromString(jwt.getClaimAsString("sid"))
        );
    }

    @MutationMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    boolean revokeDeviceSession(
        @Argument UUID id,
        @AuthenticationPrincipal Jwt jwt
    ) {
        identityService.revokeDeviceSession(
            UUID.fromString(jwt.getSubject()),
            id
        );
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    ViewerView updateViewerProfile(
        @Argument @NotBlank @Size(max = 80) String displayName,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return identityService.updateProfile(
            UUID.fromString(jwt.getSubject()),
            displayName
        );
    }
}
