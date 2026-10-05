package koboolean.example.outbound;

import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.Usage;
import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.enums.NodeMessageType;
import com.inframesh.node.monitor.ActiveRequestCounter;
import koboolean.example.service.WorkerService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Worker-side regression test for Inference over Outbound Connection, after the connection moved
 * into infra-node's SDK: boots the real Worker application in OUTBOUND mode against a fake Console
 * socket and drives REQUEST -> Worker REQUEST handler -> WorkerService -> RESPONSE over the wire.
 * Connection internals (reconnect, heartbeat, backoff, ...) are covered by infra-node's own tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboundInferenceIntegrationTest {

    private static final UUID NODE_ID = UUID.fromString("7d4c1f0e-3f6a-4b8e-9d2a-1c5e8f7a6b3d");
    private static final FakeConsoleServer CONSOLE = startConsole();

    @Autowired
    private OutboundNodeConnection connection;

    @Autowired
    private ActiveRequestCounter activeRequestCounter;

    @MockitoBean
    private WorkerService workerService;

    @DynamicPropertySource
    static void outboundProperties(DynamicPropertyRegistry registry) {
        registry.add("inframesh.node.connection-mode", () -> "OUTBOUND");
        registry.add("inframesh.node.console-url", CONSOLE::baseUrl);
        registry.add("inframesh.node.node-id", NODE_ID::toString);
        registry.add("inframesh.node.credential", () -> "worker-test-credential");
        registry.add("inframesh.node.outbound.heartbeat-interval", () -> "30s");
    }

    @AfterAll
    static void stopConsole() throws IOException {
        CONSOLE.close();
    }

    @BeforeEach
    void awaitConnected() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!connection.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(connection.isConnected()).isTrue();
    }

    @Test
    void request_isServedByWorkerServiceAndRespondedWithSameRequestId() throws Exception {
        when(workerService.invoke(any(WorkerRequest.class)))
                .thenAnswer(invocation -> {
                    WorkerRequest request = invocation.getArgument(0);
                    return new ChatResponse("echo:" + request.messages().get(0).content(), new Usage(1L, 2L, 3L));
                });

        CONSOLE.sendTextFrame(requestFrame("req-1", "session-1", "hi"));

        JsonNode response = awaitEnvelope(NodeMessageType.RESPONSE, "req-1");
        assertThat(response.path("nodeId").asString()).isEqualTo(NODE_ID.toString());
        ChatResponse payload = JsonMapper.shared().treeToValue(response.path("payload"), ChatResponse.class);
        assertThat(payload.message()).isEqualTo("echo:hi");
        assertThat(payload.usage().totalTokens()).isEqualTo(3L);
    }

    @Test
    void request_isCountedOnceBySdkWhileRunning_notAgainByTheWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(workerService.invoke(any(WorkerRequest.class))).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return new ChatResponse("done", null);
        });

        assertThat(activeRequestCounter.get()).isZero();
        CONSOLE.sendTextFrame(requestFrame("req-count", null, "hi"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // 2 here would mean the Worker counts on top of infra-node's OUTBOUND lifecycle.
        assertThat(activeRequestCounter.get()).isEqualTo(1);

        release.countDown();
        awaitEnvelope(NodeMessageType.RESPONSE, "req-count");
        awaitActiveRequests(0);
    }

    @Test
    void inferenceFailure_isReportedAsErrorEnvelope() throws Exception {
        when(workerService.invoke(any(WorkerRequest.class))).thenThrow(new IllegalStateException("model unavailable"));

        CONSOLE.sendTextFrame(requestFrame("req-err", null, "hi"));

        JsonNode error = awaitEnvelope(NodeMessageType.ERROR, "req-err");
        assertThat(error.path("payload").path("message").asString()).isEqualTo("model unavailable");
    }

    @Test
    void concurrentRequests_areEachCorrelatedToTheirOwnResponse() throws Exception {
        when(workerService.invoke(any(WorkerRequest.class)))
                .thenAnswer(invocation -> {
                    WorkerRequest request = invocation.getArgument(0);
                    return new ChatResponse("echo:" + request.messages().get(0).content(), null);
                });

        for (int i = 0; i < 5; i++) {
            CONSOLE.sendTextFrame(requestFrame("req-c" + i, null, "m" + i));
        }

        Map<String, String> answers = new HashMap<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (answers.size() < 5 && System.nanoTime() < deadline) {
            String frame = CONSOLE.awaitTextFrame(1);
            if (frame == null) {
                continue;
            }
            JsonNode envelope = JsonMapper.shared().readTree(frame);
            if (NodeMessageType.RESPONSE.name().equals(envelope.path("type").asString())) {
                answers.put(envelope.path("requestId").asString(), envelope.path("payload").path("message").asString());
            }
        }

        assertThat(answers).containsOnly(
                Map.entry("req-c0", "echo:m0"), Map.entry("req-c1", "echo:m1"), Map.entry("req-c2", "echo:m2"),
                Map.entry("req-c3", "echo:m3"), Map.entry("req-c4", "echo:m4"));
    }

    private void awaitActiveRequests(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (activeRequestCounter.get() != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(activeRequestCounter.get()).isEqualTo(expected);
    }

    private static FakeConsoleServer startConsole() {
        try {
            return new FakeConsoleServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String requestFrame(String requestId, String sessionId, String userMessage) {
        WorkerRequest request = new WorkerRequest(sessionId, List.of(ChatMessage.user(userMessage)), null, null, null, null);
        return JsonMapper.shared().writeValueAsString(new NodeEnvelope<>(
                UUID.randomUUID().toString(), NodeMessageType.REQUEST, NODE_ID, Instant.now(), requestId, request));
    }

    private JsonNode awaitEnvelope(NodeMessageType type, String requestId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = CONSOLE.awaitTextFrame(1);
            if (frame == null) {
                continue;
            }
            JsonNode envelope = JsonMapper.shared().readTree(frame);
            if (type.name().equals(envelope.path("type").asString()) && requestId.equals(envelope.path("requestId").asString())) {
                return envelope;
            }
        }
        throw new AssertionError("Timed out waiting for " + type + " requestId=" + requestId);
    }
}
