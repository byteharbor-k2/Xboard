package com.sinx.platform.node.websocket;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.node.application.NodeMachineService;
import com.sinx.platform.node.application.NodeProtocolService;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.ProxyNodeRepository;

@Service
@Transactional
public class NodeWebSocketAuthenticator {

    private final NodeMachineService machines;
    private final NodeProtocolService protocol;
    private final ProxyNodeRepository nodes;

    public NodeWebSocketAuthenticator(
        NodeMachineService machines,
        NodeProtocolService protocol,
        ProxyNodeRepository nodes
    ) {
        this.machines = machines;
        this.protocol = protocol;
        this.nodes = nodes;
    }

    public NodeWebSocketAuthContext machine(long machineId, String token) {
        machines.authenticate(machineId, token);
        List<Long> nodeIds = nodes
            .findByMachineIdAndEnabledTrueOrderBySortOrderAscIdAsc(machineId)
            .stream()
            .map(ProxyNode::getId)
            .toList();
        return new NodeWebSocketAuthContext(machineId, token, null, nodeIds);
    }

    public NodeWebSocketAuthContext node(long nodeId, String token) {
        ProxyNode node = protocol.authenticateLegacy(nodeId, token).node();
        return new NodeWebSocketAuthContext(0, token, node.getId(), List.of(node.getId()));
    }

    public NodeWebSocketAuthContext refresh(NodeWebSocketAuthContext auth) {
        return auth.machineMode()
            ? machine(auth.machineId(), auth.token())
            : node(auth.nodeId(), auth.token());
    }
}
