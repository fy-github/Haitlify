package com.haitlify.aitest.platform.ai.visual;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
class AiVisualAssetControllerTest {

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
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from projects where code = 'AI_VISUAL'");
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_VISUAL",
            "AI Visual Project",
            "Visual asset tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_VISUAL'", Long.class);
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, vision_model, embedding_model,
              api_key_ciphertext, credential_configured, request_timeout_ms, max_input_length,
              max_output_length, retry_count, request_logging_enabled, external_document_send_allowed,
              provider_config_json, masked_config_json, vision_enabled, max_image_bytes, allowed_image_types_json,
              actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default",
            true,
            "openai-compatible",
            "https://api.example.com/v1",
            "gpt-text",
            "gpt-vision",
            "",
            "sk-test",
            true,
            30000,
            0,
            0,
            0,
            false,
            false,
            null,
            "****test",
            true,
            4096,
            "[\"image/png\"]",
            "system-dev",
            "Development User",
            now,
            now
        );
    }

    @Test
    void uploadPngCreatesVisualAssetWithDimensionsAndHash() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/ai/visual-assets")
                .file(pngFile())
                .param("projectId", String.valueOf(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assetId").isNotEmpty())
            .andExpect(jsonPath("$.projectId").value(projectId))
            .andExpect(jsonPath("$.fileName").value("login.png"))
            .andExpect(jsonPath("$.contentType").value("image/png"))
            .andExpect(jsonPath("$.contentHash").isNotEmpty())
            .andExpect(jsonPath("$.byteSize").value(greaterThan(0)))
            .andExpect(jsonPath("$.width").value(8))
            .andExpect(jsonPath("$.height").value(6))
            .andExpect(jsonPath("$.actorId").value("system"))
            .andReturn();

        JsonNode response = objectMapper.readTree(upload.getResponse().getContentAsString());
        String assetId = response.get("assetId").asText();

        mockMvc.perform(get("/api/ai/visual-assets/{assetId}", assetId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assetId").value(assetId))
            .andExpect(jsonPath("$.width").value(8))
            .andExpect(jsonPath("$.height").value(6));
    }

    @Test
    void uploadSameImageReturnsSameContentHash() throws Exception {
        String firstHash = objectMapper.readTree(uploadPng().getResponse().getContentAsString()).get("contentHash").asText();
        String secondHash = objectMapper.readTree(uploadPng().getResponse().getContentAsString()).get("contentHash").asText();

        org.assertj.core.api.Assertions.assertThat(secondHash).isEqualTo(firstHash);
    }

    @Test
    void unsupportedFileTypeIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/ai/visual-assets")
                .file(file)
                .param("projectId", String.valueOf(projectId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Unsupported visual asset type")));
    }

    @Test
    void oversizedFileIsRejectedFromConfig() throws Exception {
        jdbcTemplate.update("update ai_system_configs set max_image_bytes = 4 where config_key = 'default'");

        mockMvc.perform(multipart("/api/ai/visual-assets")
                .file(pngFile())
                .param("projectId", String.valueOf(projectId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Visual asset is too large")));
    }

    private MvcResult uploadPng() throws Exception {
        return mockMvc.perform(multipart("/api/ai/visual-assets")
                .file(pngFile())
                .param("projectId", String.valueOf(projectId)))
            .andExpect(status().isOk())
            .andReturn();
    }

    private MockMultipartFile pngFile() throws Exception {
        return new MockMultipartFile("file", "login.png", "image/png", pngBytes());
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
