package koboolean.example;

import com.inframesh.node.connection.NodeRequestHandler;
import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import koboolean.example.service.WorkerService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WorkerWebMvcExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerWebMvcExampleApplication.class, args);
    }

    /**
     * Worker REQUEST handler: serves REQUESTs received over the OUTBOUND persistent connection (only
     * wired up when {@code inframesh.node.connection-mode: OUTBOUND} makes infra-node's Outbound
     * Connection SDK create the connection) with the same inference pipeline DIRECT's
     * {@code POST /api/v1/invoke} uses, so the two transports never diverge in behavior.
     */
    @Bean
    public NodeRequestHandler<WorkerRequest, ChatResponse> outboundRequestHandler(WorkerService workerService) {
        return NodeRequestHandler.of(WorkerRequest.class, workerService::invoke);
    }
}
