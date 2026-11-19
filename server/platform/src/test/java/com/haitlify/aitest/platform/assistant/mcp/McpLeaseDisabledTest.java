package com.haitlify.aitest.platform.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 默认关闭即「不存在」：{@code app.assistant.mcp.lease.enabled=false} 时 /mcp-lease/** 对所有方法都是
 * 404，而且上下文里没有租约注册表 Bean——不是「有个空壳注册表但拒绝」，是压根没有持有 capability 的地方。
 */
@SpringBootTest(properties = {"app.security.enabled=false",
    "app.assistant.mcp.enabled=true",
    "spring.datasource.url=jdbc:h2:mem:mcp-lease-disabled;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class McpLeaseDisabledTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test void leasePathDoesNotExistAndNoRegistryIsCreated() throws Exception {
        assertThat(context.getBeanNamesForType(McpLeaseRegistry.class)).isEmpty();
        for (String method : new String[]{"GET", "POST", "DELETE", "OPTIONS", "PUT", "PATCH", "HEAD"}) {
            mvc.perform(request(HttpMethod.valueOf(method), "/mcp-lease/" + "A".repeat(43)))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(request(HttpMethod.valueOf(method), "/mcp-lease"))
                .andExpect(status().isNotFound());
        }
    }
}
