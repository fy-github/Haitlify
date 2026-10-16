package com.haitlify.aitest.platform.webautomation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.web-automation.runner.enabled=false")
class WebAutomationRunControllerDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsNotImplementedWhenRunnerIsDisabled() throws Exception {
        mockMvc.perform(post("/api/web-automation/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mode": "sequential",
                      "cases": []
                    }
                    """))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.message").value("Web automation runner is not enabled"));
    }
}
