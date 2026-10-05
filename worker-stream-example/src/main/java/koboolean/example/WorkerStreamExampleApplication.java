package koboolean.example;

import com.inframesh.node.connection.NodeRequestHandler;
import com.inframesh.node.connection.NodeStreamRequestHandler;
import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.ChatStreamResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import koboolean.example.service.WorkerService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WorkerStreamExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerStreamExampleApplication.class, args);
    }

    /**
     * Worker REQUEST handler: serves non-streaming REQUESTs received over the OUTBOUND persistent
     * connection with the same inference pipeline DIRECT's {@code POST /api/v1/invoke} uses. The
     * connection itself (WebSocket, auth, heartbeat, reconnect, lifecycle) comes from infra-node's
     * Outbound Connection SDK, and this bean is only picked up when
     * {@code inframesh.node.connection-mode: OUTBOUND}.
     *
     * The SDK counts the request in {@code ActiveRequestCounter} for as long as this handler runs,
     * so it must not be counted again here - or in {@code WorkerService}.
     */
    @Bean
    public NodeRequestHandler<WorkerRequest, ChatResponse> outboundRequestHandler(WorkerService workerService) {
        return NodeRequestHandler.of(WorkerRequest.class, request -> workerService.invoke(request).block());
    }

    /**
     * Worker STREAM_REQUEST handler: the streaming counterpart of {@link #outboundRequestHandler},
     * backed by the same pipeline as DIRECT's {@code POST /api/v1/stream}. The SDK owns the whole
     * stream lifecycle (STREAM_CHUNK sequencing, STREAM_COMPLETE/STREAM_ERROR, CANCEL, connection
     * loss) and keeps {@code ActiveRequestCounter} from STREAM_REQUEST until the stream ends.
     */
    @Bean
    public NodeStreamRequestHandler<WorkerRequest, ChatStreamResponse> outboundStreamRequestHandler(WorkerService workerService) {
        return NodeStreamRequestHandler.of(WorkerRequest.class, workerService::stream);
    }
}
