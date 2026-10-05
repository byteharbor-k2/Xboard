package com.sinx.platform.identity.graphql;

import java.util.UUID;

import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.identity.application.InvitationService;
import com.sinx.platform.identity.application.InvitationService.InvitationCodeView;
import com.sinx.platform.identity.application.InvitationService.ViewerInvitationSummary;
import com.sinx.platform.identity.domain.InviteCode;

@Controller
public class InvitationQueryController {

    private final InvitationService invitations;

    public InvitationQueryController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    ViewerInvitationSummary viewerInvitations(
        @AuthenticationPrincipal Jwt jwt
    ) {
        return invitations.summary(userId(jwt));
    }

    @MutationMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    InvitationCodeView createInvitationCode(
        @AuthenticationPrincipal Jwt jwt
    ) {
        InviteCode code = invitations.create(userId(jwt));
        return new InvitationCodeView(
            code.getId(),
            code.getCode(),
            code.getCreatedAt()
        );
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
