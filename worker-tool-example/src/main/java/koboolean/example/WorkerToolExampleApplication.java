package koboolean.example;

import com.inframesh.node.connection.NodeRequestHandler;
import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import koboolean.example.service.WorkerService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WorkerToolExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerToolExampleApplication.class, args);
    }

    /**
     * Worker REQUEST handler: serves REQUESTs received over the OUTBOUND persistent connection with
     * the same inference pipeline DIRECT's {@code POST /api/v1/invoke} uses. infra-node's Outbound
     * Connection SDK invokes it off the WebSocket listener thread, so blocking on the reactive
     * pipeline here is safe.
     */
    @Bean
    public NodeRequestHandler<WorkerRequest, ChatResponse> outboundRequestHandler(WorkerService workerService) {
        return NodeRequestHandler.of(WorkerRequest.class, request -> workerService.invoke(request).block());
    }
}
