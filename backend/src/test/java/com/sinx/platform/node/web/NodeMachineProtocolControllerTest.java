package com.sinx.platform.node.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sinx.platform.node.application.NodeMachineService;
import com.sinx.platform.node.application.NodeManagementService;

class NodeMachineProtocolControllerTest {

    @Test
    void acceptsStatusWithoutOptionalSwapDiskAndNetworkMetrics() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new NodeMachineProtocolController(machines, mock(NodeManagementService.class))
        ).build();

        mvc.perform(post("/api/v2/server/machine/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "machine_id": 2,
                      "token": "token",
                      "cpu": 12.5,
                      "mem": {"total": 1024, "used": 256}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        ArgumentCaptor<NodeMachineService.MachineStatus> status =
            ArgumentCaptor.forClass(NodeMachineService.MachineStatus.class);
        verify(machines).recordStatus(
            org.mockito.ArgumentMatchers.eq(2L),
            org.mockito.ArgumentMatchers.eq("token"),
            status.capture()
        );
        assertThat(status.getValue().swap()).isNull();
        assertThat(status.getValue().disk()).isNull();
        assertThat(status.getValue().net()).isNull();
    }
}
