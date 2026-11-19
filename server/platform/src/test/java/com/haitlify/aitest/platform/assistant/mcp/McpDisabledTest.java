package com.haitlify.aitest.platform.assistant.mcp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.security.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:mcp-disabled;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class McpDisabledTest {
    @Autowired MockMvc mvc;
    @Test void defaultClosedEvenWhenApplicationSecurityIsDisabled() throws Exception {
        for (String method : new String[]{"GET", "POST", "DELETE", "OPTIONS", "PUT"}) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/mcp"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
    }
}
