package koboolean.example.controller;

import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import koboolean.example.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DIRECT inference must show up as {@code NodeHealthResponse.runtime.activeRequests} on the real
 * {@code GET /api/v1/health} endpoint for exactly as long as it runs - also when it fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectActiveRequestsTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Value("${infra.node.api-key}")
    private String apiKey;

    @MockitoBean
    private WorkerService workerService;

    @Test
    void concurrentInvokes_areCountedWhileRunningAndReleasedOneByOne() throws Exception {
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        when(workerService.invoke(any(WorkerRequest.class))).thenAnswer(invocation -> {
            WorkerRequest request = invocation.getArgument(0);
            CountDownLatch release = "a".equals(request.sessionId()) ? releaseA : releaseB;
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return new ChatResponse("done", null);
        });

        assertThat(activeRequests()).isZero();

        CompletableFuture<HttpResponse<String>> a = invokeAsync("a");
        awaitActiveRequests(1);

        CompletableFuture<HttpResponse<String>> b = invokeAsync("b");
        awaitActiveRequests(2);

        releaseA.countDown();
        assertThat(a.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        awaitActiveRequests(1);

        releaseB.countDown();
        assertThat(b.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        awaitActiveRequests(0);
    }

    @Test
    void failingInvoke_isCountedWhileRunningAndReleasedOnException() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(workerService.invoke(any(WorkerRequest.class))).thenAnswer(invocation -> {
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("model unavailable");
        });

        CompletableFuture<HttpResponse<String>> response = invokeAsync("fail");
        awaitActiveRequests(1);

        release.countDown();
        assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(500);
        awaitActiveRequests(0);
    }

    private CompletableFuture<HttpResponse<String>> invokeAsync(String sessionId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/invoke"))
                .header("X-Infra-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"sessionId": "%s", "messages": [{"role": "USER", "content": "hi"}]}
                        """.formatted(sessionId)))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
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
                .header("X-Infra-Api-Key", apiKey)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonMapper.shared().readValue(response.body(), NodeHealthResponse.class).runtime().activeRequests();
    }
}
