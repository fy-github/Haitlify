package com.haitlify.aitest.platform.template;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsDefaultsAndPersistsProjectInterfaceTemplate() throws Exception {
        long projectId = createProject("TPL");

        mockMvc.perform(get("/api/projects/{projectId}/case-template/interface", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("interface"))
            .andExpect(jsonPath("$.schemaVersion").value(2))
            .andExpect(jsonPath("$.interfaceFields[0].key").value("caseId"))
            .andExpect(jsonPath("$.interfaceFields[0].label").value("用例ID"))
            .andExpect(jsonPath("$.caseTypes[0].id").value("positive"))
            .andExpect(jsonPath("$.caseTypes[0].name").value("正向"));

        mockMvc.perform(put("/api/projects/{projectId}/case-template/interface", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "schemaVersion": 2,
                      "interfaceFields": [
                        {
                          "key": "caseId",
                          "label": "用例编号",
                          "type": "text",
                          "required": true,
                          "visible": true,
                          "exportable": true,
                          "aiGenerated": false
                        }
                      ],
                      "caseTypes": [
                        {
                          "id": "positive",
                          "name": "正常场景",
                          "appliesTo": "all",
                          "enabled": true
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.interfaceFields[0].label").value("用例编号"))
            .andExpect(jsonPath("$.caseTypes[0].name").value("正常场景"))
            .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mockMvc.perform(get("/api/projects/{projectId}/case-template/interface", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.interfaceFields[0].label").value("用例编号"))
            .andExpect(jsonPath("$.caseTypes[0].name").value("正常场景"))
            .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    private long createProject(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "%s",
                      "name": "%s Project",
                      "description": "template test project"
                    }
                    """.formatted(code, code)))
            .andExpect(status().isCreated())
            .andReturn();

        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }
}
