package com.sinx.platform.subscription.graphql;

import java.util.List;
import java.util.UUID;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.subscription.application.ViewerNodeService;
import com.sinx.platform.subscription.application.ViewerNodeService.ViewerNode;

@Controller
public class ViewerNodeController {

    private final ViewerNodeService viewerNodes;

    public ViewerNodeController(ViewerNodeService viewerNodes) {
        this.viewerNodes = viewerNodes;
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    List<ViewerNode> viewerNodes(@AuthenticationPrincipal Jwt jwt) {
        return viewerNodes.viewerNodes(UUID.fromString(jwt.getSubject()));
    }
}
