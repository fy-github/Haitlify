package com.haitlify.aitest.platform.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.ObjectMapper;
import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;

/**
 * 平台级 Hermes 真实模型 E2E：走平台自己的 runtime → TCP gateway → 容器内 Hermes → 真实模型。
 *
 * <p>默认跳过；需要先按 scripts/e2e/hermes-model-e2e.sh 把容器 9420 临时映射到本机，
 * 并设置 HAITLIFY_HERMES_E2E=true（以及 HAITLIFY_HERMES_E2E_PORT，默认 19420）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.assistant.hermes.transport=tcp",
    "app.assistant.hermes.host=127.0.0.1",
    "app.assistant.hermes.port=${HAITLIFY_HERMES_E2E_PORT:19420}",
    "app.security.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "HAITLIFY_HERMES_E2E", matches = "true")
class HermesModelEndToEndTest {

    @Autowired
    private HermesGateway gateway;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void platformRuntimeSubmitsRealPromptAndHermesAcceptsIt() {
        HermesAssistantRuntime runtime = new HermesAssistantRuntime(gateway, mapper);
        assertThat(runtime.available()).as("Hermes 执行单元必须可用（否则 E2E 无意义）").isTrue();

        HermesAssistantRuntime.Submission submission = runtime.submit(
            "/state", "只回复两个字：收到。不要使用任何工具。");

        assertThat(submission.sessionId()).isNotBlank();
        assertThat(submission.state()).isEqualTo(HermesAssistantRuntime.State.SUBMITTED);
    }
}
