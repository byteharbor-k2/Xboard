package com.sinx.platform.node.websocket;

import java.util.List;

public record NodeWebSocketAuthContext(
    long machineId,
    String token,
    Long nodeId,
    List<Long> nodeIds
) {
    public NodeWebSocketAuthContext {
        nodeIds = List.copyOf(nodeIds);
    }

    public boolean machineMode() {
        return nodeId == null;
    }
}
