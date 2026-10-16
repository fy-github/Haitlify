package com.haitlify.aitest.platform.project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;

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
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsAndListsProjects() throws Exception {
        mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "HAITLIFY",
                      "name": "Haitlify API",
                      "description": "Haitlify platform API test workspace"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("HAITLIFY"))
            .andExpect(jsonPath("$.name").value("Haitlify API"))
            .andExpect(jsonPath("$.description").value("Haitlify platform API test workspace"))
            .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/projects"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].code").value(hasItem("HAITLIFY")))
            .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void createReturnsExistingProjectForDuplicateCode() throws Exception {
        String payload = """
            {
              "code": "HAITLIFY_DUP",
              "name": "Haitlify Duplicate",
              "description": "Original workspace"
            }
            """;

        mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "HAITLIFY_DUP",
                      "name": "Haitlify Duplicate Renamed",
                      "description": "Duplicate workspace"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("HAITLIFY_DUP"))
            .andExpect(jsonPath("$.name").value("Haitlify Duplicate"))
            .andExpect(jsonPath("$.description").value("Original workspace"));
    }
}
