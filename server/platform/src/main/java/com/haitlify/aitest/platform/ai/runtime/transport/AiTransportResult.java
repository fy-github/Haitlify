package com.haitlify.aitest.platform.ai.runtime.transport;

import com.haitlify.aitest.platform.ai.runtime.AiProtocolResponse;

public record AiTransportResult(int httpStatus, AiProtocolResponse response, Long durationMillis, Long ttfbMillis) {

    /** 兼容旧调用点的便捷构造：未计测时为 null（指标侧不得把 null 当 0）。 */
    public AiTransportResult(int httpStatus, AiProtocolResponse response) {
        this(httpStatus, response, null, null);
    }
}
