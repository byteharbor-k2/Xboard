package com.sinx.platform.node.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sinx.platform.node.application.NodeMachineService;
import com.sinx.platform.node.application.NodeManagementService;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.node.websocket.NodeWebSocketChangeNotifier;

class AdminNodeMachineControllerTest {

    @Test
    void disablingMachineRevokesItsActiveWebSocket() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        NodeWebSocketChangeNotifier notifier = mock(NodeWebSocketChangeNotifier.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeMachineController(
                machines,
                mock(NodeManagementService.class),
                mock(PlatformConfigurationService.class),
                notifier
            )
        ).build();

        mvc.perform(post("/api/v2/admin/server/machine/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":9,"name":"Machine 9","notes":"disabled","is_active":false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(machines).update(9L, "Machine 9", "disabled", false);
        verify(notifier).machineRevoked(9L);
    }

    @Test
    void keepingMachineActiveDoesNotRevokeItsWebSocket() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        NodeWebSocketChangeNotifier notifier = mock(NodeWebSocketChangeNotifier.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeMachineController(
                machines,
                mock(NodeManagementService.class),
                mock(PlatformConfigurationService.class),
                notifier
            )
        ).build();

        mvc.perform(post("/api/v2/admin/server/machine/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":9,"name":"Machine 9","notes":"active","is_active":true}
                    """))
            .andExpect(status().isOk());

        verify(machines).update(9L, "Machine 9", "active", true);
        verify(notifier, never()).machineRevoked(9L);
    }

    @Test
    void rotatingMachineTokenRevokesItsExistingWebSocket() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        NodeWebSocketChangeNotifier notifier = mock(NodeWebSocketChangeNotifier.class);
        when(machines.rotateToken(9L)).thenReturn("new-token");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeMachineController(
                machines,
                mock(NodeManagementService.class),
                mock(PlatformConfigurationService.class),
                notifier
            )
        ).build();

        mvc.perform(post("/api/v2/admin/server/machine/resetToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":9}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").value("new-token"));

        verify(machines).rotateToken(9L);
        verify(notifier).machineRevoked(9L);
    }

    @Test
    void deletingMachineDisconnectsItsActiveWebSocket() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        NodeWebSocketChangeNotifier notifier = mock(NodeWebSocketChangeNotifier.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeMachineController(
                machines,
                mock(NodeManagementService.class),
                mock(PlatformConfigurationService.class),
                notifier
            )
        ).build();

        mvc.perform(post("/api/v2/admin/server/machine/drop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":9}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(machines).delete(9L);
        verify(notifier).machineDeleted(9L);
    }

    @Test
    void tokenAndInstallCommandUseTheOriginalGetEndpoints() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        PlatformConfigurationService configuration = mock(PlatformConfigurationService.class);
        when(machines.token(2L)).thenReturn("machine-token");
        when(configuration.appUrl()).thenReturn(java.util.Optional.empty());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeMachineController(
                machines,
                mock(NodeManagementService.class),
                configuration,
                mock(NodeWebSocketChangeNotifier.class)
            )
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

    @Test
    void installCommandPrefersTheConfiguredPublicApplicationUrl() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        PlatformConfigurationService configuration = mock(PlatformConfigurationService.class);
        when(machines.token(7L)).thenReturn("machine-token");
        when(configuration.appUrl()).thenReturn(
            java.util.Optional.of("https://public.example.test/panel/")
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeMachineController(
                machines,
                mock(NodeManagementService.class),
                configuration,
                mock(NodeWebSocketChangeNotifier.class)
            )
        ).build();

        mvc.perform(get("/api/v2/admin/server/machine/installCommand")
                .param("id", "7")
                .with(request -> {
                    request.setScheme("http");
                    request.setServerName("127.0.0.1");
                    request.setServerPort(8080);
                    return request;
                }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.command").value(
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString(
                        "--panel 'https://public.example.test/panel'"
                    ),
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("--panel 'http://127.0.0.1:8080'")
                    ),
                    org.hamcrest.Matchers.containsString("--machine-id 7")
                )
            ));
    }
}
