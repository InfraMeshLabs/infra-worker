package koboolean.example.outbound;

import com.inframesh.node.connection.NodeRequestHandler;
import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import koboolean.example.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The Worker REQUEST handler this example registers with infra-node's Outbound Connection SDK:
 * it must reuse the DIRECT inference pipeline (WorkerService), and - with the default DIRECT
 * configuration - no outbound connection may be created at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboundRequestHandlerTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private NodeRequestHandler<WorkerRequest, ChatResponse> handler;

    @MockitoBean
    private WorkerService workerService;

    @Test
    void directModeByDefault_createsNoOutboundConnection() {
        assertThat(context.getBeanProvider(OutboundNodeConnection.class).getIfAvailable()).isNull();
    }

    @Test
    void handler_acceptsWorkerRequestAndDelegatesToInferenceService() {
        when(workerService.invoke(any(WorkerRequest.class))).thenReturn(new ChatResponse("hi", null));

        assertThat(handler.requestType()).isEqualTo(WorkerRequest.class);
        assertThat(handler.handle(new WorkerRequest(List.of(ChatMessage.user("hello")), null)).message()).isEqualTo("hi");
    }

    @Test
    void handler_propagatesInferenceFailure_soSdkReportsError() {
        when(workerService.invoke(any(WorkerRequest.class))).thenThrow(new IllegalStateException("model unavailable"));

        assertThatThrownBy(() -> handler.handle(new WorkerRequest(List.of(ChatMessage.user("hello")), null)))
                .hasMessageContaining("model unavailable");
    }
}
