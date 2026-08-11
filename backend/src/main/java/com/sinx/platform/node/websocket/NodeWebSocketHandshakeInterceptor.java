package com.sinx.platform.node.websocket;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.sinx.platform.configuration.application.PlatformConfigurationService;

@Component
public class NodeWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String AUTH_CONTEXT = "nodeWebSocketAuth";

    private final NodeWebSocketAuthenticator authenticator;
    private final PlatformConfigurationService configuration;

    public NodeWebSocketHandshakeInterceptor(
        NodeWebSocketAuthenticator authenticator,
        PlatformConfigurationService configuration
    ) {
        this.authenticator = authenticator;
        this.configuration = configuration;
    }

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes
    ) {
        try {
            if (!configuration.nodeCommunicationSettings().webSocketEnabled()) {
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return false;
            }
        } catch (RuntimeException exception) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        MultiValueMap<String, String> query = UriComponentsBuilder
            .fromUri(request.getURI())
            .build()
            .getQueryParams();
        String token = query.getFirst("token");
        Long machineId = positiveLong(query.getFirst("machine_id"));
        Long nodeId = positiveLong(query.getFirst("node_id"));
        if (token == null || token.isBlank() || (machineId == null) == (nodeId == null)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        try {
            NodeWebSocketAuthContext auth = machineId != null
                ? authenticator.machine(machineId, token)
                : authenticator.node(nodeId, token);
            attributes.put(AUTH_CONTEXT, auth);
            return true;
        } catch (RuntimeException exception) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Exception exception
    ) {
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
