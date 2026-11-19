package com.haitlify.aitest.platform.assistant.execution;

import com.haitlify.aitest.platform.assistant.security.capability.CapabilityTokenCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Internal beans only; this package intentionally declares no controller. */
@Configuration
public class ExecutionBindingConfiguration {
    @Bean
    CapabilityTokenCodec capabilityTokenCodec() {
        return new CapabilityTokenCodec();
    }
}