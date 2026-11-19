package com.haitlify.aitest.platform.assistant.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 幂等键派生：确定性、域分离、缺密钥/坏密钥时 fail-closed（绝不退化成可预测值）。 */
class AssistantIdempotencyKeysTest {

    private static final String VALID_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";
    private static final String DIGEST = "c".repeat(64);

    @Test
    void derivationIsDeterministicAndScoped() {
        AssistantIdempotencyKeys keys = new AssistantIdempotencyKeys(VALID_KEY);
        String first = keys.derive(1L, "op-1", "rev-1", "web.contact.create", DIGEST);
        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(keys.derive(1L, "op-1", "rev-1", "web.contact.create", DIGEST)).isEqualTo(first);
        assertThat(keys.derive(2L, "op-1", "rev-1", "web.contact.create", DIGEST)).isNotEqualTo(first);
        assertThat(keys.derive(1L, "op-2", "rev-1", "web.contact.create", DIGEST)).isNotEqualTo(first);
        assertThat(keys.derive(1L, "op-1", "rev-2", "web.contact.create", DIGEST)).isNotEqualTo(first);
        assertThat(keys.derive(1L, "op-1", "rev-1", "web.contact.update", DIGEST)).isNotEqualTo(first);
        assertThat(keys.derive(1L, "op-1", "rev-1", "web.contact.create", "d".repeat(64))).isNotEqualTo(first);
    }

    @Test
    void missingKeyFailsClosed() {
        AssistantIdempotencyKeys keys = new AssistantIdempotencyKeys("");
        assertThatThrownBy(() -> keys.derive(1L, "op-1", "rev-1", "web.contact.create", DIGEST))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_SECRET_KEY");
    }

    @Test
    void invalidKeyLengthFailsClosed() {
        AssistantIdempotencyKeys keys = new AssistantIdempotencyKeys("c2hvcnQ=");
        assertThatThrownBy(() -> keys.derive(1L, "op-1", "rev-1", "web.contact.create", DIGEST))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 字节");
    }

    @Test
    void blankParametersAreRejected() {
        AssistantIdempotencyKeys keys = new AssistantIdempotencyKeys(VALID_KEY);
        assertThatThrownBy(() -> keys.derive(1L, " ", "rev-1", "web.contact.create", DIGEST))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("operationId");
        assertThatThrownBy(() -> keys.derive(1L, "op-1", "", "web.contact.create", DIGEST))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targetRevision");
        assertThatThrownBy(() -> keys.derive(1L, "op-1", "rev-1", "", DIGEST))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actionCode");
        assertThatThrownBy(() -> keys.derive(1L, "op-1", "rev-1", "web.contact.create", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputDigest");
    }
}
