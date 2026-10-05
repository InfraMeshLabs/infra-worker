package koboolean.example.controller;

import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.ChatStreamResponse;
import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import koboolean.example.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DIRECT inference must show up as {@code NodeHealthResponse.runtime.activeRequests} on the real
 * {@code GET /api/v1/health} endpoint for exactly as long as it runs: an invoke until its response,
 * a stream from subscription until it completes, fails or the client goes away.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "INFRAMESH_CONNECTION_MODE=DIRECT",
        "infra.node.api-key=" + DirectActiveRequestsTest.API_KEY
})
class DirectActiveRequestsTest {

    static final String API_KEY = "0f2c6a8e-4b1d-4c3a-9e7f-5a6b7c8d9e0f";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @MockitoBean
    private WorkerService workerService;

    @Test
    void concurrentInvokes_areCountedWhileRunningAndReleasedOneByOne() throws Exception {
        Sinks.One<ChatResponse> a = Sinks.one();
        Sinks.One<ChatResponse> b = Sinks.one();
        when(workerService.invoke(any(WorkerRequest.class))).thenAnswer(invocation -> {
            WorkerRequest request = invocation.getArgument(0);
            return ("a".equals(request.sessionId()) ? a : b).asMono();
        });

        assertThat(activeRequests()).isZero();

        CompletableFuture<HttpResponse<String>> responseA = postAsync("/api/v1/invoke", "a");
        awaitActiveRequests(1);

        CompletableFuture<HttpResponse<String>> responseB = postAsync("/api/v1/invoke", "b");
        awaitActiveRequests(2);

        a.tryEmitValue(new ChatResponse("done", null));
        assertThat(responseA.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        awaitActiveRequests(1);

        b.tryEmitValue(new ChatResponse("done", null));
        assertThat(responseB.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        awaitActiveRequests(0);
    }

    @Test
    void failingInvoke_isCountedWhileRunningAndReleasedOnError() throws Exception {
        Sinks.One<ChatResponse> result = Sinks.one();
        when(workerService.invoke(any(WorkerRequest.class))).thenReturn(result.asMono());

        CompletableFuture<HttpResponse<String>> response = postAsync("/api/v1/invoke", "fail");
        awaitActiveRequests(1);

        result.tryEmitError(new IllegalStateException("model unavailable"));
        assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(500);
        awaitActiveRequests(0);
    }

    @Test
    void invokeThrowingBeforeReturningItsMono_leavesNoCountBehind() throws Exception {
        when(workerService.invoke(any(WorkerRequest.class))).thenThrow(new IllegalStateException("bad request"));

        assertThat(postAsync("/api/v1/invoke", "throw").get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(500);
        awaitActiveRequests(0);
    }

    @Test
    void stream_isCountedUntilItCompletes() throws Exception {
        Sinks.Many<ChatStreamResponse> chunks = Sinks.many().unicast().onBackpressureBuffer();
        when(workerService.stream(any(WorkerRequest.class))).thenReturn(chunks.asFlux());

        CompletableFuture<HttpResponse<String>> response = postAsync("/api/v1/stream", "stream");
        awaitActiveRequests(1);

        // Still streaming: handing out chunks must not release the count.
        chunks.tryEmitNext(new ChatStreamResponse("Hel"));
        chunks.tryEmitNext(new ChatStreamResponse("lo"));
        assertThat(activeRequests()).isEqualTo(1);

        chunks.tryEmitComplete();
        assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        awaitActiveRequests(0);
    }

    @Test
    void stream_isReleasedWhenItFails() throws Exception {
        Sinks.Many<ChatStreamResponse> chunks = Sinks.many().unicast().onBackpressureBuffer();
        when(workerService.stream(any(WorkerRequest.class))).thenReturn(chunks.asFlux());

        postAsync("/api/v1/stream", "stream-error");
        awaitActiveRequests(1);

        chunks.tryEmitError(new IllegalStateException("model unavailable"));
        awaitActiveRequests(0);
    }

    @Test
    void stream_isReleasedWhenTheClientDisconnects() throws Exception {
        Sinks.Many<ChatStreamResponse> chunks = Sinks.many().unicast().onBackpressureBuffer();
        when(workerService.stream(any(WorkerRequest.class))).thenReturn(chunks.asFlux());

        HttpRequest request = post("/api/v1/stream", "stream-cancel")
                .header("Accept", "application/x-ndjson")
                .build();
        CompletableFuture<HttpResponse<InputStream>> response =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        awaitActiveRequests(1);

        chunks.tryEmitNext(new ChatStreamResponse("Hel"));
        InputStream body = response.get(10, TimeUnit.SECONDS).body();
        assertThat(body.read()).isNotEqualTo(-1);
        assertThat(activeRequests()).isEqualTo(1);

        // The stream never completes on its own; only the client going away can end it.
        body.close();
        awaitActiveRequests(0);
    }

    private CompletableFuture<HttpResponse<String>> postAsync(String path, String sessionId) {
        return httpClient.sendAsync(post(path, sessionId).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder post(String path, String sessionId) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Infra-Api-Key", API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"sessionId": "%s", "messages": [{"role": "USER", "content": "hi"}]}
                        """.formatted(sessionId)));
    }

    private void awaitActiveRequests(int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        int actual = activeRequests();
        while (actual != expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
            actual = activeRequests();
        }
        assertThat(actual).isEqualTo(expected);
    }

    private int activeRequests() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/health"))
                .header("X-Infra-Api-Key", API_KEY)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonMapper.shared().readValue(response.body(), NodeHealthResponse.class).runtime().activeRequests();
    }
}
