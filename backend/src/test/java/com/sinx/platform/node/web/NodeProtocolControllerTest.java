package com.sinx.platform.node.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sinx.platform.node.application.NodeProtocolService;
import com.sinx.platform.shared.web.ApiProblemException;

class NodeProtocolControllerTest {

    @Test
    void rejectsMalformedReportsBeforeCallingTheProtocolService() {
        NodeProtocolController controller = new NodeProtocolController(
            mock(NodeProtocolService.class)
        );

        assertThatThrownBy(() -> controller.report(Map.of(
            "machine_id", 1,
            "node_id", 2
        )))
            .isInstanceOf(ApiProblemException.class)
            .extracting(exception -> ((ApiProblemException) exception).getCode())
            .isEqualTo("INVALID_NODE_REPORT");
    }

    @Test
    void routesReportsWithoutMachineIdThroughLegacyAuthentication() {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        NodeProtocolController controller = new NodeProtocolController(protocol);

        controller.report(Map.of(
            "node_id", 7,
            "token", "global-token",
            "traffic", Map.of("12", java.util.List.of(4, 8))
        ));

        verify(protocol).reportLegacy(
            7L,
            "global-token",
            Map.of("traffic", Map.of("12", java.util.List.of(4, 8)))
        );
    }
}
