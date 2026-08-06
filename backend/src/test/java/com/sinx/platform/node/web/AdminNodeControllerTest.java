package com.sinx.platform.node.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sinx.platform.node.application.NodeManagementService;

class AdminNodeControllerTest {

    @Test
    void acceptsBatchUpdatesWithoutMachineFields() throws Exception {
        NodeManagementService nodes = mock(NodeManagementService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminNodeController(nodes)).build();

        mvc.perform(post("/api/v2/admin/server/manage/batchUpdate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ids":[1],"show":false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(nodes).batchUpdate(List.of(1L), false, null, null, false);
    }
}
