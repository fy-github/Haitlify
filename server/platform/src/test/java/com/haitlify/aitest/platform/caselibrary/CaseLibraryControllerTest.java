package com.haitlify.aitest.platform.caselibrary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseLibraryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void savesAndUpdatesInterfaceCasesByLibraryId() throws Exception {
        long projectId = createProject("CASELIB");

        mockMvc.perform(post("/api/projects/{projectId}/interface-cases", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(casePayload("Haitlify 获取项目树-正常请求成功")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inserted").value(1))
            .andExpect(jsonPath("$.updated").value(0))
            .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/projects/{projectId}/interface-cases", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].libraryId").value("Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向"))
            .andExpect(jsonPath("$.items[0].row.title").value("Haitlify 获取项目树-正常请求成功"))
            .andExpect(jsonPath("$.items[0].metadata.sourceOperationId").value("getProjectTree"))
            .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(post("/api/projects/{projectId}/interface-cases", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(casePayload("Haitlify 获取项目树-更新后的标题")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inserted").value(0))
            .andExpect(jsonPath("$.updated").value(1))
            .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/projects/{projectId}/interface-cases", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].row.title").value("Haitlify 获取项目树-更新后的标题"))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void deletesInterfaceCasesByLibraryIdAndIgnoresMissingIds() throws Exception {
        long projectId = createProject("CASEDEL");

        mockMvc.perform(post("/api/projects/{projectId}/interface-cases", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoCasesPayload()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inserted").value(2))
            .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(post("/api/projects/{projectId}/interface-cases/delete", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "libraryIds": [
                        "Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向",
                        "missing-library-id"
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(1))
            .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/projects/{projectId}/interface-cases", projectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].libraryId").value("Haitlify.openapi.json:createProject:/v4/pm/projects:正向"))
            .andExpect(jsonPath("$.total").value(1));
    }

    private long createProject(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "%s",
                      "name": "%s Project",
                      "description": "case library test project"
                    }
                    """.formatted(code, code)))
            .andExpect(status().isCreated())
            .andReturn();

        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private String casePayload(String title) {
        return """
            {
              "cases": [
                {
                  "id": "API-0001",
                  "libraryId": "Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向",
                  "savedAt": "2026-06-15T09:30:00Z",
                  "row": {
                    "caseId": "API-0001",
                    "project": "Haitlify API",
                    "module": "项目管理",
                    "interfaceName": "Haitlify 获取项目树",
                    "method": "GET",
                    "path": "/v4/pm/project-tree",
                    "title": "%s",
                    "caseType": "正向",
                    "executionResult": "未执行"
                  },
                  "metadata": {
                    "sourceFile": "Haitlify.openapi.json",
                    "sourceOperationId": "getProjectTree",
                    "method": "GET",
                    "path": "/v4/pm/project-tree",
                    "expectedStatus": 200,
                    "runOrder": 1
                  }
                }
              ]
            }
            """.formatted(title);
    }

    private String twoCasesPayload() {
        return """
            {
              "cases": [
                {
                  "id": "API-0001",
                  "libraryId": "Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向",
                  "savedAt": "2026-06-15T09:30:00Z",
                  "row": {
                    "caseId": "API-0001",
                    "project": "Haitlify API",
                    "module": "项目管理",
                    "interfaceName": "Haitlify 获取项目树",
                    "method": "GET",
                    "path": "/v4/pm/project-tree",
                    "title": "Haitlify 获取项目树-正常请求成功",
                    "caseType": "正向",
                    "executionResult": "未执行"
                  },
                  "metadata": {
                    "sourceFile": "Haitlify.openapi.json",
                    "sourceOperationId": "getProjectTree",
                    "method": "GET",
                    "path": "/v4/pm/project-tree",
                    "expectedStatus": 200,
                    "runOrder": 1
                  }
                },
                {
                  "id": "API-0002",
                  "libraryId": "Haitlify.openapi.json:createProject:/v4/pm/projects:正向",
                  "savedAt": "2026-06-15T09:31:00Z",
                  "row": {
                    "caseId": "API-0002",
                    "project": "Haitlify API",
                    "module": "项目管理",
                    "interfaceName": "创建项目",
                    "method": "POST",
                    "path": "/v4/pm/projects",
                    "title": "创建项目-正常请求成功",
                    "caseType": "正向",
                    "executionResult": "未执行"
                  },
                  "metadata": {
                    "sourceFile": "Haitlify.openapi.json",
                    "sourceOperationId": "createProject",
                    "method": "POST",
                    "path": "/v4/pm/projects",
                    "expectedStatus": 200,
                    "runOrder": 2
                  }
                }
              ]
            }
            """;
    }
}
