package com.haitlify.aitest.platform.jira;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JiraConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JiraConfigRepository jiraConfigRepository;

    @BeforeEach
    void cleanJiraConfig() {
        jiraConfigRepository.deleteAll();
    }

    @Test
    void returnsDefaultsFromYmlWhenNothingSaved() throws Exception {
        mockMvc.perform(get("/api/jira/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.baseUrl").value("https://pm.example.test"))
            .andExpect(jsonPath("$.authMode").value("service-account"))
            .andExpect(jsonPath("$.username").value("jira-reader"))
            .andExpect(jsonPath("$.maskedToken").value("****oken"))
            .andExpect(jsonPath("$.projectJql.HAITLIFY_API").value("project = HAITLIFY order by created DESC"));
    }

    @Test
    void savesConfigAndMasksTokenOnRead() throws Exception {
        mockMvc.perform(put("/api/jira/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "enabled": true,
                      "baseUrl": "https://jira.example.test",
                      "authMode": "service-account",
                      "username": "qa-bot",
                      "token": "secret-token-1234",
                      "projectJql": { "DEMO": "project = DEMO order by created DESC" }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.baseUrl").value("https://jira.example.test"))
            .andExpect(jsonPath("$.username").value("qa-bot"))
            .andExpect(jsonPath("$.credentialConfigured").value(true))
            .andExpect(jsonPath("$.maskedToken").value("****1234"))
            .andExpect(jsonPath("$.projectJql.DEMO").value("project = DEMO order by created DESC"));

        mockMvc.perform(get("/api/jira/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.maskedToken").value("****1234"))
            .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void testConnectionReportsSuccessForHealthyJira() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/2/myself", exchange -> respond(exchange, 200, "{\"name\":\"qa-bot\"}"));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            mockMvc.perform(post("/api/jira/config/test-connection")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "baseUrl": "http://127.0.0.1:%d", "username": "qa-bot", "token": "t" }
                        """.formatted(server.getAddress().getPort())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testConnectionReportsFailureForAuthError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/2/myself",
            exchange -> respond(exchange, 401, "{\"errorMessages\":[\"nope\"]}"));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            mockMvc.perform(post("/api/jira/config/test-connection")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "baseUrl": "http://127.0.0.1:%d", "username": "qa-bot", "token": "wrong" }
                        """.formatted(server.getAddress().getPort())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("401")));
        } finally {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
