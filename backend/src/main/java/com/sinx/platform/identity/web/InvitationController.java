package com.sinx.platform.identity.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.identity.application.InvitationService;
import com.sinx.platform.identity.application.InvitationService.InvitationCodeView;
import com.sinx.platform.identity.domain.InviteCode;

@RestController
@RequestMapping("/session/invitations")
public class InvitationController {

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @GetMapping
    List<InvitationCodeView> available(@AuthenticationPrincipal Jwt jwt) {
        return invitations.available(userId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    InvitationCodeView create(@AuthenticationPrincipal Jwt jwt) {
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
