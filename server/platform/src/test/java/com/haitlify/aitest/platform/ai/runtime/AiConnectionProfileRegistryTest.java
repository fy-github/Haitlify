package com.haitlify.aitest.platform.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.*;

class AiConnectionProfileRegistryTest {
    @Test
    void profileRequiresRegisteredConnectionAndKeepsVersions() {
        var registry = new AiConnectionProfileRegistry();
        var connection = new AiConnection("c1", 2, "https://provider.invalid", "secret-ref", Protocol.RESPONSES, true, List.of(7L));
        registry.register(connection);
        var profile = new AiModelProfile("p1", 4, "c1", "model-x", List.of(Capability.TEXT, Capability.JSON_SCHEMA), null);
        registry.register(profile);
        assertEquals(2, registry.connection("c1").version());
        assertEquals(4, registry.profile("p1").version());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new AiModelProfile("p2", 1, "missing", "m", List.of(), null)));
    }
}
