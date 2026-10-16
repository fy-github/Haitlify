package com.haitlify.aitest.platform.document;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FigmaDesignControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsDraftSummaryWhenTokenIsNotConfigured() throws Exception {
        mockMvc.perform(post("/api/requirement-documents/figma/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "url": "https://www.figma.com/design/AbCdEf123456/Login-Page?node-id=12-34&m=dev"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentType").value("figma"))
            .andExpect(jsonPath("$.text").value(containsString("Figma 设计链接")))
            .andExpect(jsonPath("$.text").value(containsString("AbCdEf123456")))
            .andExpect(jsonPath("$.visualSummary.componentHints[0]").value(containsString("Figma API")))
            .andExpect(jsonPath("$.suggestedRequirementPoints[0]").value(containsString("布局结构")));
    }
}
