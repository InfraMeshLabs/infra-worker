package koboolean.example.outbound;

import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.enums.NodeMessageType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker-side regression test for OUTBOUND Health Reporting: boots the real Worker application in
 * OUTBOUND mode against a fake Console socket and confirms the real, Worker-collected
 * {@code NodeHealthService} output is what infra-node pushes as HEALTH - separately from HEARTBEAT,
 * per {@code CONNECTED != HEALTHY}. HEALTH scheduling/backoff/shutdown mechanics themselves are
 * infra-node's own responsibility and are covered exhaustively by its test suite; this only proves
 * the Worker wiring (no HEALTH-reporting code exists here - infra-node's auto-configuration finds
 * the real {@code NodeHealthService} bean on its own) produces the right thing end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboundHealthReportingIntegrationTest {

    private static final UUID NODE_ID = UUID.fromString("2a1f9c3e-6b7d-4e2a-9f1c-8d3b5a7e0c1f");
    private static final String CREDENTIAL = "worker-health-test-credential";
    private static final FakeConsoleServer CONSOLE = startConsole();

    @Autowired
    private OutboundNodeConnection connection;

    @MockitoBean
    private WorkerService workerService;

    @DynamicPropertySource
    static void outboundProperties(DynamicPropertyRegistry registry) {
        registry.add("inframesh.node.connection-mode", () -> "OUTBOUND");
        registry.add("inframesh.node.console-url", CONSOLE::baseUrl);
        registry.add("inframesh.node.node-id", NODE_ID::toString);
        registry.add("inframesh.node.credential", () -> CREDENTIAL);
        registry.add("inframesh.node.outbound.heartbeat-interval", () -> "1s");
        registry.add("inframesh.node.outbound.health-interval", () -> "500ms");
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
    void connect_pushesHealthReportCarryingRealNodeHealthServiceData() throws Exception {
        JsonNode envelope = awaitEnvelope(NodeMessageType.HEALTH, 5);
        assertThat(envelope.path("nodeId").asString()).isEqualTo(NODE_ID.toString());

        NodeHealthResponse health =
                JsonMapper.shared().treeToValue(envelope.path("payload"), NodeHealthResponse.class);
        // Real NodeHealthService output (this machine's CPU/memory), not a stub - proves the Worker's
        // actual health bean is what infra-node's auto-configuration found and wired in.
        assertThat(health.status().name()).isEqualTo("UP");
        assertThat(health.system().cpu()).isNotNull();
        assertThat(health.system().memory()).isNotNull();
        assertThat(health.runtime().activeRequests()).isNotNull();
    }

    @Test
    void heartbeatAndHealth_areSentAsDistinctEnvelopesWithDistinctPayloads() throws Exception {
        JsonNode heartbeat = awaitEnvelope(NodeMessageType.HEARTBEAT, 10);
        JsonNode health = awaitEnvelope(NodeMessageType.HEALTH, 10);

        // NodeHeartbeat carries no fields; a HEALTH payload must never be squeezed into it.
        assertThat(heartbeat.path("payload").properties()).isEmpty();
        assertThat(health.path("payload").has("status")).isTrue();
        assertThat(health.path("payload").has("system")).isTrue();
    }

    @Test
    void healthAck_isAcceptedWithoutDisconnecting() throws Exception {
        awaitEnvelope(NodeMessageType.HEALTH, 5);

        CONSOLE.sendTextFrame(JsonMapper.shared().writeValueAsString(new NodeEnvelope<>(
                UUID.randomUUID().toString(), NodeMessageType.HEALTH_ACK, NODE_ID, Instant.now(), null, null)));

        Thread.sleep(200);
        assertThat(connection.isConnected()).isTrue();
    }

    @Test
    void credential_neverAppearsInHealthEnvelope() throws Exception {
        JsonNode health = awaitEnvelope(NodeMessageType.HEALTH, 5);

        assertThat(health.toString()).doesNotContain(CREDENTIAL);
    }

    private static FakeConsoleServer startConsole() {
        try {
            return new FakeConsoleServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode awaitEnvelope(NodeMessageType type, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = CONSOLE.awaitTextFrame(1);
            if (frame == null) {
                continue;
            }
            JsonNode envelope = JsonMapper.shared().readTree(frame);
            if (type.name().equals(envelope.path("type").asString())) {
                return envelope;
            }
        }
        throw new AssertionError("Timed out waiting for " + type);
    }
}
