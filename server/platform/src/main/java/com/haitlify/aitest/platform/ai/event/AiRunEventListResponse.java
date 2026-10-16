package com.haitlify.aitest.platform.ai.event;

import java.util.List;

public record AiRunEventListResponse(
    List<AiRunEvent> items
) {
}
