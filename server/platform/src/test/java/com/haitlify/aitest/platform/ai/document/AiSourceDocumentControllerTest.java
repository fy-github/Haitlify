package com.haitlify.aitest.platform.ai.document;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiSourceDocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_visual_analysis_results");
        jdbcTemplate.update("delete from ai_visual_regions");
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from ai_visual_assets");
        jdbcTemplate.update("delete from projects where code in ('AI_SRC_DOC', 'AI_SRC_DOC_B')");
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_SRC_DOC",
            "AI Source Document Project",
            "Source document tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_SRC_DOC'", Long.class);
    }

    @Test
    void uploadsSourceDocumentAndListsEvidenceChunks() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/ai/source-documents")
                .file(requirementFile())
                .param("projectId", String.valueOf(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentId").isNotEmpty())
            .andExpect(jsonPath("$.projectId").value(projectId))
            .andExpect(jsonPath("$.fileName").value("requirements.txt"))
            .andExpect(jsonPath("$.documentType").value("text"))
            .andExpect(jsonPath("$.contentHash").isNotEmpty())
            .andExpect(jsonPath("$.parserVersion").value("requirement-document-parser-v1"))
            .andExpect(jsonPath("$.actorId").value("system"))
            .andExpect(jsonPath("$.actorName").value("System"))
            .andReturn();

        JsonNode response = objectMapper.readTree(upload.getResponse().getContentAsString());
        String documentId = response.get("documentId").asText();

        mockMvc.perform(get("/api/ai/source-documents/{documentId}", documentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentId").value(documentId))
            .andExpect(jsonPath("$.extractedText").value(containsString("用户输入正确账号密码后进入工作台")));

        mockMvc.perform(get("/api/ai/source-documents/{documentId}/chunks", documentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(greaterThan(0)))
            .andExpect(jsonPath("$.items[0].chunkId").isNotEmpty())
            .andExpect(jsonPath("$.items[0].sourceFile").value("requirements.txt"))
            .andExpect(jsonPath("$.items[0].chunkType").value("text"))
            .andExpect(jsonPath("$.items[0].contentText").value(containsString("用户输入正确账号密码后进入工作台")))
            .andExpect(jsonPath("$.items[0].evidenceMetadata.fileName").value("requirements.txt"));
    }

    @Test
    void listsProjectDocumentsNewestFirstAndKeepsOtherProjectsOut() throws Exception {
        MvcResult first = uploadRequirementFile();
        Thread.sleep(5);
        MvcResult second = uploadRequirementFile();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("documentId").asText();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("documentId").asText();

        // 另一项目的文档必须被隔离
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_SRC_DOC_B", "Other Project", "", true, now, now);
        Long otherProject = jdbcTemplate.queryForObject(
            "select id from projects where code = 'AI_SRC_DOC_B'", Long.class);
        jdbcTemplate.update("""
            insert into ai_source_documents(id, document_id, project_id, file_name, document_type,
              content_hash, storage_location, extracted_text, parse_metadata_json, parser_version,
              source_file_metadata_json, actor_id, actor_name, created_at, updated_at)
            values (9201, 'doc-other', ?, 'other.txt', 'text', 'h-other', '', 'x', '{}', 'v0', '{}',
              'system', 'System', ?, ?)
            """, otherProject, now, now);

        mockMvc.perform(get("/api/ai/source-documents").param("projectId", String.valueOf(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].documentId").value(secondId))
            .andExpect(jsonPath("$.items[1].documentId").value(firstId))
            .andExpect(jsonPath("$.items[0].fileName").value("requirements.txt"))
            .andExpect(jsonPath("$.items[0].parserVersion").value("requirement-document-parser-v1"));

        mockMvc.perform(get("/api/ai/source-documents").param("projectId", String.valueOf(otherProject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].documentId").value("doc-other"));
    }

    @Test
    void computesStableContentHashForSameFileContent() throws Exception {
        MvcResult first = uploadRequirementFile();
        MvcResult second = uploadRequirementFile();

        String firstHash = objectMapper.readTree(first.getResponse().getContentAsString()).get("contentHash").asText();
        String secondHash = objectMapper.readTree(second.getResponse().getContentAsString()).get("contentHash").asText();

        org.assertj.core.api.Assertions.assertThat(secondHash).isEqualTo(firstHash);
    }

    @Test
    void uploadsImageRequirementFileAndLinksVisualAsset() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/ai/source-documents")
                .file(imageRequirementFile())
                .param("projectId", String.valueOf(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentType").value("image"))
            .andExpect(jsonPath("$.visualAssetId").isNotEmpty())
            .andReturn();

        JsonNode response = objectMapper.readTree(upload.getResponse().getContentAsString());
        String documentId = response.get("documentId").asText();
        String visualAssetId = response.get("visualAssetId").asText();

        mockMvc.perform(get("/api/ai/source-documents/{documentId}", documentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.visualAssetId").value(visualAssetId));

        Integer linkedCount = jdbcTemplate.queryForObject(
            """
            select count(*)
            from ai_source_documents d
            join ai_visual_assets a on a.id = d.visual_asset_id
            where d.document_id = ? and a.asset_id = ?
            """,
            Integer.class,
            documentId,
            visualAssetId
        );
        org.assertj.core.api.Assertions.assertThat(linkedCount).isEqualTo(1);
    }

    private MvcResult uploadRequirementFile() throws Exception {
        return mockMvc.perform(multipart("/api/ai/source-documents")
                .file(requirementFile())
                .param("projectId", String.valueOf(projectId)))
            .andExpect(status().isOk())
            .andReturn();
    }

    private MockMultipartFile requirementFile() {
        return new MockMultipartFile(
            "file",
            "requirements.txt",
            "text/plain",
            """
                项目: Haitlify
                模块: 登录
                页面: 登录页
                测试点: 用户输入正确账号密码后进入工作台
                测试点: 密码错误时显示错误提示
                """.getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile imageRequirementFile() throws Exception {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile("file", "requirements.png", "image/png", output.toByteArray());
    }
}
