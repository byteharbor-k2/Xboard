package com.sinx.platform.node.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class NodeWebSocketConfiguration implements WebSocketConfigurer {

    private final NodeWebSocketHandler handler;
    private final NodeWebSocketHandshakeInterceptor authentication;

    public NodeWebSocketConfiguration(
        NodeWebSocketHandler handler,
        NodeWebSocketHandshakeInterceptor authentication
    ) {
        this.handler = handler;
        this.authentication = authentication;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
            .addInterceptors(authentication);
    }
}
