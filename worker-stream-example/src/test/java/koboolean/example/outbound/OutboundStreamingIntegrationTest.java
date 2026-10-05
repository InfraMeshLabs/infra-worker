package koboolean.example.outbound;

import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.ChatStreamResponse;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Worker-side regression test for Streaming Inference over the Outbound Connection: boots the real
 * Worker application in OUTBOUND mode against a fake Console socket and drives
 * STREAM_REQUEST -> WorkerService.stream() -> STREAM_CHUNK(s), then STREAM_COMPLETE/STREAM_ERROR, and CANCEL,
 * over the wire. Connection internals (reconnect, heartbeat, health, ...) are covered by infra-node's
 * own tests; non-streaming REQUEST/RESPONSE is covered by {@link OutboundRequestHandlerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboundStreamingIntegrationTest {

    private static final UUID NODE_ID = UUID.fromString("9c3e2a1f-7d6b-4e2a-9f1c-0c1f8d3b5a7e");
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
        registry.add("inframesh.node.credential", () -> "worker-stream-test-credential");
        registry.add("inframesh.node.outbound.heartbeat-interval", () -> "30s");
        registry.add("inframesh.node.outbound.health-interval", () -> "0s");
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
    void streamRequest_isServedByWorkerServiceWithChunksThenComplete() throws Exception {
        when(workerService.stream(any(WorkerRequest.class)))
                .thenReturn(Flux.just(new ChatStreamResponse("Hel"), new ChatStreamResponse("lo"),
                        new ChatStreamResponse("!")));

        CONSOLE.sendTextFrame(streamRequestFrame("req-s1", "hi"));

        for (long expectedSequence = 0; expectedSequence < 3; expectedSequence++) {
            JsonNode chunk = awaitEnvelope(NodeMessageType.STREAM_CHUNK, "req-s1");
            assertThat(chunk.path("nodeId").asString()).isEqualTo(NODE_ID.toString());
            assertThat(chunk.path("payload").path("sequence").asLong()).isEqualTo(expectedSequence);
        }

        JsonNode complete = awaitEnvelope(NodeMessageType.STREAM_COMPLETE, "req-s1");
        assertThat(complete.path("payload").path("chunkCount").asLong()).isEqualTo(3L);
    }

    @Test
    void stream_isCountedOnceBySdkUntilItCompletes_notAgainByTheWorker() throws Exception {
        Sinks.Many<ChatStreamResponse> chunks = Sinks.many().unicast().onBackpressureBuffer();
        when(workerService.stream(any(WorkerRequest.class))).thenReturn(chunks.asFlux());

        assertThat(activeRequestCounter.get()).isZero();
        CONSOLE.sendTextFrame(streamRequestFrame("req-count", "hi"));
        chunks.tryEmitNext(new ChatStreamResponse("Hel"));
        awaitEnvelope(NodeMessageType.STREAM_CHUNK, "req-count");

        // 2 here would mean the Worker counts on top of infra-node's OUTBOUND stream lifecycle.
        assertThat(activeRequestCounter.get()).isEqualTo(1);

        chunks.tryEmitComplete();
        awaitEnvelope(NodeMessageType.STREAM_COMPLETE, "req-count");
        awaitActiveRequests(0);
    }

    @Test
    void streamingFailure_isReportedAsStreamErrorWithoutLeakingInternals() throws Exception {
        when(workerService.stream(any(WorkerRequest.class)))
                .thenReturn(Flux.error(new IllegalStateException("model unavailable")));

        CONSOLE.sendTextFrame(streamRequestFrame("req-err", "hi"));

        JsonNode error = awaitEnvelope(NodeMessageType.STREAM_ERROR, "req-err");
        assertThat(error.path("payload").path("message").asString()).isEqualTo("model unavailable");
        assertThat(error.toString()).doesNotContain("IllegalStateException").doesNotContain("at koboolean");
    }

    @Test
    void malformedStreamRequestPayload_isReportedAsStreamError() throws Exception {
        String requestId = "req-malformed";
        String badFrame = JsonMapper.shared().writeValueAsString(new NodeEnvelope<>(
                UUID.randomUUID().toString(), NodeMessageType.STREAM_REQUEST, NODE_ID, Instant.now(), requestId,
                "not-a-worker-request"));

        CONSOLE.sendTextFrame(badFrame);

        JsonNode error = awaitEnvelope(NodeMessageType.STREAM_ERROR, requestId);
        assertThat(error.path("payload").path("code").asString()).isEqualTo("MALFORMED_PAYLOAD");
    }

    @Test
    void cancel_disposesTheUnderlyingSubscriptionAndStopsFurtherChunks() throws Exception {
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        when(workerService.stream(any(WorkerRequest.class)))
                .thenReturn(Flux.interval(Duration.ofMillis(50))
                        .map(tick -> new ChatStreamResponse("tick" + tick))
                        .doOnCancel(() -> upstreamCancelled.set(true)));

        CONSOLE.sendTextFrame(streamRequestFrame("req-cancel", "hi"));
        awaitEnvelope(NodeMessageType.STREAM_CHUNK, "req-cancel");
        assertThat(activeRequestCounter.get()).isEqualTo(1);

        CONSOLE.sendTextFrame(cancelFrame("req-cancel"));

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!upstreamCancelled.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(upstreamCancelled.get()).isTrue();
        awaitActiveRequests(0);

        // Drain whatever was already in flight, then confirm nothing further ever arrives.
        drainFramesFor(Duration.ofMillis(300));
        assertThat(awaitEnvelopeOptional(NodeMessageType.STREAM_CHUNK, "req-cancel", 1)).isNull();
        assertThat(awaitEnvelopeOptional(NodeMessageType.STREAM_COMPLETE, "req-cancel", 1)).isNull();
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

    private String streamRequestFrame(String requestId, String userMessage) {
        WorkerRequest request = new WorkerRequest(null, List.of(ChatMessage.user(userMessage)), null, null, null, null);
        return JsonMapper.shared().writeValueAsString(new NodeEnvelope<>(
                UUID.randomUUID().toString(), NodeMessageType.STREAM_REQUEST, NODE_ID, Instant.now(), requestId,
                request));
    }

    private String cancelFrame(String requestId) {
        return JsonMapper.shared().writeValueAsString(new NodeEnvelope<>(
                UUID.randomUUID().toString(), NodeMessageType.CANCEL, NODE_ID, Instant.now(), requestId, null));
    }

    private void drainFramesFor(Duration duration) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            CONSOLE.awaitTextFrame(1);
        }
    }

    private JsonNode awaitEnvelope(NodeMessageType type, String requestId) throws InterruptedException {
        JsonNode envelope = awaitEnvelopeOptional(type, requestId, 5);
        if (envelope == null) {
            throw new AssertionError("Timed out waiting for " + type + " requestId=" + requestId);
        }
        return envelope;
    }

    private JsonNode awaitEnvelopeOptional(NodeMessageType type, String requestId, long timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = CONSOLE.awaitTextFrame(1);
            if (frame == null) {
                continue;
            }
            JsonNode envelope = JsonMapper.shared().readTree(frame);
            if (type.name().equals(envelope.path("type").asString())
                    && requestId.equals(envelope.path("requestId").asString())) {
                return envelope;
            }
        }
        return null;
    }
}
