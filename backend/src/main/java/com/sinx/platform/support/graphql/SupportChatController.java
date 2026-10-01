package com.sinx.platform.support.graphql;

import java.util.List;
import java.util.UUID;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.support.application.SupportChatService;
import com.sinx.platform.support.application.SupportChatService.MessageView;

@Controller
public class SupportChatController {

    private final SupportChatService support;

    public SupportChatController(SupportChatService support) {
        this.support = support;
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    List<MessageView> viewerSupportMessages(@AuthenticationPrincipal Jwt jwt) {
        return support.userMessages(UUID.fromString(jwt.getSubject()));
    }

    @MutationMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    MessageView sendSupportMessage(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String content
    ) {
        return support.sendUserMessage(UUID.fromString(jwt.getSubject()), content);
    }
}
