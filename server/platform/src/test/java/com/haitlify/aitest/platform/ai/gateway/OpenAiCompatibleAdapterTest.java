package com.haitlify.aitest.platform.ai.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

class OpenAiCompatibleAdapterTest {

    @Test
    void testConnectionUsesCompletionTokenFieldCompatibleWithReasoningModels() {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
            {"choices":[{"message":{"content":"pong"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
            """);
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(httpClient);

        AiConnectionTestResult result = adapter.testConnection(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "gpt-5.5", "sk-test", 30000)
        );

        assertTrue(result.success());
        assertEquals("连接成功", result.message());
        assertTrue(httpClient.lastBody.contains("\"max_completion_tokens\":1"));
        assertFalse(httpClient.lastBody.contains("\"max_tokens\""));
    }

    @Test
    void structuredGenerateFailsWhenProviderReturnsNoMessageContent() {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
            {"choices":[{"message":{"reasoning_content":"internal reasoning"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
            """);
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(httpClient);

        AiGenerationResult result = adapter.structuredGenerate(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "reasoning-model", "sk-test", 30000),
            "generate test points"
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("空"));
    }

    @Test
    void structuredGenerateReadsTextPartsWhenMessageContentIsAnArray() {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
            {"choices":[{"message":{"content":[{"type":"text","text":"{\\"draftTestPoints\\":[]}"}]}}],"usage":{}}
            """);
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(httpClient);

        AiGenerationResult result = adapter.structuredGenerate(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "structured-model", "sk-test", 30000),
            "generate test points"
        );

        assertTrue(result.success());
        assertEquals("{\"draftTestPoints\":[]}", result.content());
    }

    @Test
    void structuredGenerateSerializesObjectMessageContent() {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
            {"choices":[{"message":{"content":{"draftTestPoints":[]}}}],"usage":{}}
            """);
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(httpClient);

        AiGenerationResult result = adapter.structuredGenerate(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "structured-model", "sk-test", 30000),
            "generate test points"
        );

        assertTrue(result.success());
        assertEquals("{\"draftTestPoints\":[]}", result.content());
    }

    @Test
    void structuredGenerateDoesNotInjectAFixedCompletionTokenLimit() {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
            {"choices":[{"message":{"content":"{\\"draftTestPoints\\":[]}"}}],"usage":{}}
            """);
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(httpClient);

        AiGenerationResult result = adapter.structuredGenerate(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "gpt-5.6-sol", "sk-test", 30000),
            "generate test points"
        );

        assertTrue(result.success(), result.errorMessage());
        assertFalse(httpClient.lastBody.contains("\"max_completion_tokens\":"));
    }

    @Test
    void structuredGenerateIncludesUpstreamErrorDetail() {
        CapturingHttpClient httpClient = new CapturingHttpClient(503, """
            {"error":{"message":"model queue timed out"}}
            """);
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(httpClient);

        AiGenerationResult result = adapter.structuredGenerate(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "gpt-5.6-sol", "sk-test", 30000),
            "generate test points"
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("model queue timed out"));
    }

    @Test
    void upstreamErrorMarksUsageUnknownInsteadOfZero() {
        // T05：5xx 可能已被上游处理 ⇒ usage 记 -1（unknown），不得伪装成 0（exactly-once）。
        CapturingHttpClient httpClient = new CapturingHttpClient(503, "{\"error\":{\"message\":\"busy\"}}");
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(httpClient);

        AiGenerationResult result = adapter.structuredGenerate(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "m", "sk-test", 30000), "p");

        assertEquals(-1, result.inputTokens());
        assertEquals(-1, result.outputTokens());
    }

    @Test
    void rateLimitedResponseCarriesRetryAfterAndClassifies() {
        CapturingHttpClient httpClient = new CapturingHttpClient(429, "{\"error\":{\"message\":\"slow down\"}}",
            java.util.Map.of("retry-after", "7"));
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(httpClient);

        AiGenerationResult result = adapter.structuredGenerate(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "m", "sk-test", 30000), "p");

        assertEquals("RATE_LIMITED", result.retryReason());
        assertTrue(result.retryable());
        assertEquals(7_000L, result.retryAfterMs());
    }

    @Test
    void emptyUsableContentIsClassifiedAsRetryableEmptyOutput() {
        // T05 分类（v1）：空内容 ⇒ EMPTY_OUTPUT（可重试一次），取代旧的不可重试 OUTPUT_VALIDATION。
        CapturingHttpClient httpClient = new CapturingHttpClient(200,
            "{\"choices\":[{\"message\":{\"reasoning_content\":\"internal\"}}],\"usage\":{}}");
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(httpClient);

        AiGenerationResult result = adapter.structuredGenerate(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "m", "sk-test", 30000), "p");

        assertFalse(result.success());
        assertEquals("EMPTY_OUTPUT", result.retryReason());
        assertTrue(result.retryable());
    }

    private static final class CapturingHttpClient extends HttpClient {
        private final int statusCode;
        private final String responseBody;
        private final java.util.Map<String, String> extraHeaders;
        private String lastBody = "";

        private CapturingHttpClient(int statusCode, String responseBody) {
            this(statusCode, responseBody, java.util.Map.of());
        }

        private CapturingHttpClient(int statusCode, String responseBody, java.util.Map<String, String> extraHeaders) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
            this.extraHeaders = extraHeaders;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
            lastBody = readBody(request);
            @SuppressWarnings("unchecked")
            T body = (T) responseBody;
            return new StubHttpResponse<>(request, statusCode, body, extraHeaders);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("sendAsync is not used in tests");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            BodyHandler<T> responseBodyHandler,
            PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException("sendAsync is not used in tests");
        }

        private String readBody(HttpRequest request) throws IOException, InterruptedException {
            BodyCaptureSubscriber subscriber = new BodyCaptureSubscriber();
            request.bodyPublisher().orElseThrow().subscribe(subscriber);
            return subscriber.body();
        }
    }

    private record StubHttpResponse<T>(HttpRequest request, int statusCode, T body, java.util.Map<String, String> extraHeaders)
        implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            java.util.Map<String, List<String>> headers = new java.util.HashMap<>();
            headers.put("x-request-id", List.of("test-request-1"));
            extraHeaders.forEach((name, value) -> headers.put(name, List.of(value)));
            return HttpHeaders.of(headers, (name, value) -> true);
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }
    }

    private static final class BodyCaptureSubscriber implements Flow.Subscriber<java.nio.ByteBuffer> {
        private final StringBuilder builder = new StringBuilder();
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            this.subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.nio.ByteBuffer item) {
            builder.append(java.nio.charset.StandardCharsets.UTF_8.decode(item));
        }

        @Override
        public void onError(Throwable throwable) {
            throw new RuntimeException(throwable);
        }

        @Override
        public void onComplete() {
        }

        private String body() {
            return builder.toString();
        }
    }
}
