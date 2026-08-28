package com.sinx.platform.node.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sinx.platform.node.domain.NodeMachine;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.NodeAccessGroupRepository;
import com.sinx.platform.node.repository.NodeMachineRepository;
import com.sinx.platform.node.repository.NodeRouteRuleRepository;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.databind.ObjectMapper;

class NodeManagementServiceValidationTest {

    private final NodeManagementService service = new NodeManagementService(
        mock(ProxyNodeRepository.class),
        mock(NodeMachineRepository.class),
        mock(NodeAccessGroupRepository.class),
        mock(NodeRouteRuleRepository.class),
        new ObjectMapper(),
        Clock.systemUTC()
    );

    @Test
    void requiresAValidHostAndBothPublicAndBackendPorts() {
        assertInvalid(draft("https://node.example.test", 443, 8443,
            BigDecimal.ONE, 0L, List.of()), "Node host");
        assertInvalid(draft("node.example.test", null, 8443,
            BigDecimal.ONE, 0L, List.of()), "Public port");
        assertInvalid(draft("node.example.test", 443, 65_536,
            BigDecimal.ONE, 0L, List.of()), "Server port");
    }

    @Test
    void rejectsNegativeRateAndTrafficLimits() {
        assertInvalid(draft("node.example.test", 443, 8443,
            new BigDecimal("-0.01"), 0L, List.of()), "rate");
        assertInvalid(draft("node.example.test", 443, 8443,
            BigDecimal.ONE, -1L, List.of()), "traffic limit");
    }

    @Test
    void validatesEveryRateTimeRangeBeforePersistence() {
        assertInvalid(draft("node.example.test", 443, 8443,
            BigDecimal.ONE, 0L,
            List.of(Map.of("start", "24:00", "end", "12:00", "rate", 1))),
            "HH:mm");
        assertInvalid(draft("node.example.test", 443, 8443,
            BigDecimal.ONE, 0L,
            List.of(Map.of("start", "08:00", "end", "12:00", "rate", -1))),
            "non-negative numeric rate");
    }

    @Test
    void refusesTwoNodesSharingAListenPortOnTheSameMachine() {
        ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
        NodeMachineRepository machines = mock(NodeMachineRepository.class);
        NodeMachine machine = mock(NodeMachine.class);
        ProxyNode existing = mock(ProxyNode.class);
        when(machine.getId()).thenReturn(4L);
        when(machines.findById(4L)).thenReturn(Optional.of(machine));
        when(existing.getId()).thenReturn(77L);
        when(nodes.findByMachineIdAndServerPort(4L, 8443))
            .thenReturn(List.of(existing));
        NodeManagementService scoped = new NodeManagementService(
            nodes,
            machines,
            mock(NodeAccessGroupRepository.class),
            mock(NodeRouteRuleRepository.class),
            new ObjectMapper(),
            Clock.systemUTC()
        );

        // The agent would fail with "address already in use" and the panel
        // could not see it, so the collision is refused up front.
        assertThatThrownBy(() -> scoped.save(onMachine(4L, 8443)))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("already used by another node on this machine");
    }

    private NodeManagementService.NodeDraft onMachine(long machineId, int serverPort) {
        NodeManagementService.NodeDraft base = draft(
            "node.example.test", 443, serverPort, BigDecimal.ONE, 0L, List.of()
        );
        return new NodeManagementService.NodeDraft(
            base.id(), base.type(), base.code(), base.parentId(), machineId,
            base.groupIds(), base.routeIds(), base.name(), base.rate(),
            base.rateTimeEnable(), base.rateTimeRanges(), base.transferEnable(),
            base.tags(), base.host(), base.port(), base.serverPort(),
            base.protocolSettings(), base.customOutbounds(), base.customRoutes(),
            base.certConfig(), base.show(), base.enabled(), base.sort()
        );
    }

    private NodeManagementService.NodeDraft draft(
        String host,
        Integer port,
        Integer serverPort,
        BigDecimal rate,
        Long transferEnable,
        Object rateTimeRanges
    ) {
        return new NodeManagementService.NodeDraft(
            null,
            "shadowsocks",
            null,
            null,
            null,
            List.of(),
            List.of(),
            "Test node",
            rate,
            true,
            rateTimeRanges,
            transferEnable,
            List.of(),
            host,
            port,
            serverPort,
            Map.of("cipher", "aes-128-gcm", "listen_ip", "0.0.0.0"),
            List.of(),
            List.of(),
            null,
            true,
            true,
            null
        );
    }

    private void assertInvalid(
        NodeManagementService.NodeDraft draft,
        String expectedDetail
    ) {
        assertThatThrownBy(() -> service.save(draft))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining(expectedDetail)
            .extracting(exception -> ((ApiProblemException) exception).getCode())
            .isEqualTo("INVALID_NODE");
    }
}
