package com.haitlify.aitest.platform.assistant.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 清理证据不能是一句话；必须能指向可核验对象。 */
class CleanupEvidenceTest {

    @Test
    void plainWordsAndPlaceholdersAreRejected() {
        assertThatThrownBy(() -> CleanupEvidence.validate("已清理"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JSON");
        assertThatThrownBy(() -> CleanupEvidence.validate("{\"verifiedBy\":\"x\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("verifiedBy");
        assertThatThrownBy(() -> CleanupEvidence.validate("{\"readBack\":\"ok\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("readBack");
        assertThatThrownBy(() -> CleanupEvidence.validate("{\"reference\":\"x\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reference");
        assertThatThrownBy(() -> CleanupEvidence.validate("[]"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JSON 对象");
    }

    @Test
    void readBackArtifactAndVerifierAreAccepted() {
        assertThatCode(() -> CleanupEvidence.validate("{\"readBack\":\"absent\"}")).doesNotThrowAnyException();
        assertThatCode(() -> CleanupEvidence.validate(
            "{\"reference\":\"order-123456\",\"artifactUrl\":\"https://evidence.example/1.png\"}"))
            .doesNotThrowAnyException();
        assertThatCode(() -> CleanupEvidence.validate("{\"verifiedBy\":\"runner-01\"}")).doesNotThrowAnyException();
    }
}
