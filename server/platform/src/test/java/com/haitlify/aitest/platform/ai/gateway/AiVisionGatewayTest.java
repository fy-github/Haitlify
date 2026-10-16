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

class AiVisionGatewayTest {

    @Test
    void missingVisionModelReturnsValidationErrorWithoutCallingProvider() {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
            {"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
            """);
        AiGateway gateway = new DefaultAiGateway(List.of(new OpenAiCompatibleAdapter(httpClient)));

        AiVisionResponse result = gateway.visionAnalyze(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "gpt-text", "", "sk-test", 30000),
            new AiVisionRequest("Describe this UI", "image/png", new byte[] { 1, 2, 3 }, 512)
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("vision model"));
        assertEquals(0, httpClient.sentRequests);
    }

    @Test
    void visionRequestSendsPromptAndImageContentToConfiguredVisionModel() {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
            {
              "choices": [
                { "message": { "content": "{\\"layout\\":\\"login\\"}" } }
              ],
              "usage": { "prompt_tokens": 11, "completion_tokens": 7 }
            }
            """);
        AiGateway gateway = new DefaultAiGateway(List.of(new OpenAiCompatibleAdapter(httpClient)));

        AiVisionResponse result = gateway.visionAnalyze(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "gpt-text", "gpt-vision", "sk-test", 30000),
            new AiVisionRequest("Extract visible controls", "image/png", new byte[] { 1, 2, 3 }, 512)
        );

        assertTrue(result.success());
        assertEquals("{\"layout\":\"login\"}", result.content());
        assertEquals(11, result.inputTokens());
        assertEquals(7, result.outputTokens());
        assertTrue(httpClient.lastBody.contains("\"model\":\"gpt-vision\""));
        assertTrue(httpClient.lastBody.contains("Extract visible controls"));
        assertTrue(httpClient.lastBody.contains("data:image/png;base64,AQID"));
        assertFalse(httpClient.lastBody.contains("sk-test"));
    }

    @Test
    void nonSuccessfulVisionProviderResponseReturnsSafeFailure() {
        CapturingHttpClient httpClient = new CapturingHttpClient(401, """
            {"error":{"message":"invalid sk-test credential"}}
            """);
        AiGateway gateway = new DefaultAiGateway(List.of(new OpenAiCompatibleAdapter(httpClient)));

        AiVisionResponse result = gateway.visionAnalyze(
            new AiGatewayConfig("openai-compatible", "https://api.example.com/v1", "gpt-text", "gpt-vision", "sk-test", 30000),
            new AiVisionRequest("Extract visible controls", "image/png", new byte[] { 1, 2, 3 }, 512)
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("HTTP 401"));
        assertFalse(result.errorMessage().contains("sk-test"));
    }

    private static final class CapturingHttpClient extends HttpClient {
        private final int statusCode;
        private final String responseBody;
        private int sentRequests;
        private String lastBody = "";

        private CapturingHttpClient(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
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
            sentRequests++;
            lastBody = readBody(request);
            @SuppressWarnings("unchecked")
            T body = (T) responseBody;
            return new StubHttpResponse<>(request, statusCode, body);
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

    private record StubHttpResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of("x-request-id", List.of("vision-request-1")), (name, value) -> true);
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
            throw new IllegalStateException(throwable);
        }

        @Override
        public void onComplete() {
        }

        String body() throws InterruptedException {
            return builder.toString();
        }
    }
}
