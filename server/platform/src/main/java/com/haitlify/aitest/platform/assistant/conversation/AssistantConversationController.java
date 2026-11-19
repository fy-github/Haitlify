package com.haitlify.aitest.platform.assistant.conversation;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 助手对话的 HTTP 入口（默认关闭：{@code app.assistant.conversation.enabled=false} 时本 Bean 不存在，
 * 路由直接 404，且不会建立任何 Hermes 连接）。
 *
 * <p>契约：
 * <ul>
 *   <li>{@code POST /api/assistant/conversations} — 为当前登录主体在其有权访问的项目下建对话；
 *       同一 requestId 重放返回 200 与同一个 conversationId（首次 201）。</li>
 *   <li>{@code POST /api/assistant/conversations/{id}/messages} — 提交文本，服务端派生身份；
 *       受理成功 202 + 自己的 turnId + {@code SUBMITTED}；不可判定/不可用 503 + {@code UNKNOWN/FAILED}
 *       + {@code reconcileRequired}，绝不返回「看起来成功」的响应。</li>
 *   <li>{@code GET /api/assistant/conversations/{id}/messages?limit=&offset=} — 分页读取消息
 *       （状态与文本）；不含任何会话句柄或凭据。</li>
 * </ul>
 *
 * <p>身份字段只能来自登录主体：请求体里出现 {@code actorId/tenantId} 等未知键就是 400。
 */
@RestController
@RequestMapping("/api/assistant/conversations")
@ConditionalOnProperty(name = "app.assistant.conversation.enabled", havingValue = "true")
class AssistantConversationController {

    private final AssistantConversationService service;

    AssistantConversationController(AssistantConversationService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<AssistantConversationService.ConversationView> create(@RequestBody Map<String, Object> request) {
        AssistantConversationService.ConversationView conversation = service.create(request);
        return ResponseEntity.status(conversation.created() ? HttpStatus.CREATED : HttpStatus.OK).body(conversation);
    }

    @PostMapping("/{id}/messages")
    ResponseEntity<AssistantConversationService.SubmitReceipt> submit(@PathVariable String id,
                                                                     @RequestBody Map<String, Object> request) {
        AssistantConversationService.SubmitReceipt receipt = service.submit(id, request);
        HttpStatus status = AssistantConversationService.STATE_SUBMITTED.equals(receipt.state())
            ? HttpStatus.ACCEPTED
            : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(receipt);
    }

    @GetMapping("/{id}/messages")
    AssistantConversationService.MessagePage messages(@PathVariable String id,
                                                      @RequestParam(required = false) Integer limit,
                                                      @RequestParam(required = false) Integer offset) {
        return service.messages(id, limit, offset);
    }
}
