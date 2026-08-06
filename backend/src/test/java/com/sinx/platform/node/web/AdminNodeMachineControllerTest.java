package com.sinx.platform.node.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sinx.platform.node.application.NodeMachineService;
import com.sinx.platform.node.application.NodeManagementService;

class AdminNodeMachineControllerTest {

    @Test
    void tokenAndInstallCommandUseTheOriginalGetEndpoints() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        when(machines.token(2L)).thenReturn("machine-token");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeMachineController(machines, mock(NodeManagementService.class))
        ).build();

        mvc.perform(get("/api/v2/admin/server/machine/getToken").param("id", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").value("machine-token"));

        mvc.perform(get("/api/v2/admin/server/machine/installCommand")
                .param("id", "2")
                .with(request -> {
                    request.setScheme("https");
                    request.setSecure(true);
                    request.setServerName("panel.example.test");
                    request.setServerPort(443);
                    return request;
                }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.command").value(
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("--mode machine"),
                    org.hamcrest.Matchers.containsString("--panel 'https://panel.example.test'"),
                    org.hamcrest.Matchers.containsString("--token 'machine-token'"),
                    org.hamcrest.Matchers.containsString("--machine-id 2")
                )
            ));
    }
}
