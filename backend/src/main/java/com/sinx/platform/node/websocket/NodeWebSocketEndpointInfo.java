package com.sinx.platform.node.websocket;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.sinx.platform.configuration.application.PlatformConfigurationService;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class NodeWebSocketEndpointInfo {

    private final PlatformConfigurationService configuration;

    public NodeWebSocketEndpointInfo(PlatformConfigurationService configuration) {
        this.configuration = configuration;
    }

    public Endpoint endpoint(HttpServletRequest request) {
        PlatformConfigurationService.NodeCommunicationSettings settings =
            configuration.nodeCommunicationSettings();
        if (!settings.webSocketEnabled()) {
            return new Endpoint(false, null, settings.pushIntervalSeconds(), settings.pullIntervalSeconds());
        }
        String url = settings.webSocketUrl();
        if (url == null) {
            String httpScheme = request.getHeader("X-Forwarded-Proto");
            if (httpScheme == null || httpScheme.isBlank()) httpScheme = request.getScheme();
            String webSocketScheme = "https".equalsIgnoreCase(httpScheme) ? "wss" : "ws";
            url = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath("/ws")
                .replaceQuery(null)
                .scheme(webSocketScheme)
                .build()
                .toUriString();
        }
        return new Endpoint(true, url, settings.pushIntervalSeconds(), settings.pullIntervalSeconds());
    }

    public record Endpoint(
        boolean enabled,
        String url,
        int pushIntervalSeconds,
        int pullIntervalSeconds
    ) {
    }
}
