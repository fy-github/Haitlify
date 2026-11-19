package com.haitlify.aitest.platform.assistant.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** targetRef 必须限定在本次项目作用域内，且不能是 URL。 */
class AssistantTargetRefTest {

    @Test
    void acceptsProjectScopedReferences() {
        assertThatCode(() -> AssistantTargetRef.validate("project:7/contacts", 7L)).doesNotThrowAnyException();
        assertThatCode(() -> AssistantTargetRef.validate("case:12345", 7L)).doesNotThrowAnyException();
        assertThatCode(() -> AssistantTargetRef.validate("requirement:req-1/form", 7L)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUrlsAndUnknownNamespaces() {
        assertThatThrownBy(() -> AssistantTargetRef.validate("https://evil.example/x", 7L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能是 URL");
        assertThatThrownBy(() -> AssistantTargetRef.validate("ftp://files.example/x", 7L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能是 URL");
        assertThatThrownBy(() -> AssistantTargetRef.validate("billing:1/x", 7L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("namespace");
    }

    @Test
    void rejectsCrossProjectReferences() {
        assertThatThrownBy(() -> AssistantTargetRef.validate("project:8/contacts", 7L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("指向其它项目");
    }
}
