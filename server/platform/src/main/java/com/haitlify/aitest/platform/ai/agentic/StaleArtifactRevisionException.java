package com.haitlify.aitest.platform.ai.agentic;

/**
 * T10/T11 gate：确认/修改/驳回/重试携带的 revision 已过期——必须返回冲突而不是覆盖。
 */
public class StaleArtifactRevisionException extends RuntimeException {

    public StaleArtifactRevisionException(String message) {
        super(message);
    }
}
