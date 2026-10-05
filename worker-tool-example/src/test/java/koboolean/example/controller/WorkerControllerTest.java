package koboolean.example.controller;

import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.ChatStreamResponse;
import com.inframesh.node.dto.Usage;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.monitor.ActiveRequestCounter;
import koboolean.example.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(WorkerController.class)
@Import(ActiveRequestCounter.class)
class WorkerControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private WorkerService workerService;

    @Test
    void invoke_withSessionId_passesSessionIdToService() {
        when(workerService.invoke(any(WorkerRequest.class)))
                .thenReturn(Mono.just(new ChatResponse("hello", new Usage(1L, 1L, 2L))));

        webTestClient.post().uri("/api/v1/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "session-123",
                          "messages": [{"role": "USER", "content": "hi"}]
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.message").isEqualTo("hello");

        ArgumentCaptor<WorkerRequest> captor = ArgumentCaptor.forClass(WorkerRequest.class);
        verify(workerService).invoke(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("session-123");
    }

    @Test
    void invoke_withoutSessionId_stillInvokesNormally() {
        when(workerService.invoke(any(WorkerRequest.class)))
                .thenReturn(Mono.just(new ChatResponse("hello", new Usage(1L, 1L, 2L))));

        webTestClient.post().uri("/api/v1/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "messages": [{"role": "USER", "content": "hi"}]
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.message").isEqualTo("hello");

        ArgumentCaptor<WorkerRequest> captor = ArgumentCaptor.forClass(WorkerRequest.class);
        verify(workerService).invoke(captor.capture());
        assertThat(captor.getValue().sessionId()).isNull();
    }

    @Test
    void invoke_withSessionIdAndTools_toolCallingUnaffected() {
        when(workerService.invoke(any(WorkerRequest.class)))
                .thenReturn(Mono.just(new ChatResponse("hello", new Usage(1L, 1L, 2L))));

        webTestClient.post().uri("/api/v1/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "session-123",
                          "messages": [{"role": "USER", "content": "What's the weather in Seoul?"}],
                          "tools": [
                            {
                              "name": "get_weather",
                              "description": "Get the current weather for a given city",
                              "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}
                            }
                          ],
                          "toolChoice": {"mode": "AUTO"}
                        }
                        """)
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<WorkerRequest> captor = ArgumentCaptor.forClass(WorkerRequest.class);
        verify(workerService).invoke(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("session-123");
        assertThat(captor.getValue().tools()).hasSize(1);
    }

    @Test
    void stream_withSessionId_deserializesAndStreamsNormally() {
        when(workerService.stream(any(WorkerRequest.class)))
                .thenReturn(Flux.just(new ChatStreamResponse("chunk-1"), new ChatStreamResponse("chunk-2")));

        webTestClient.post().uri("/api/v1/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sessionId": "session-123",
                          "messages": [{"role": "USER", "content": "hi"}]
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ChatStreamResponse.class)
                .hasSize(2);

        ArgumentCaptor<WorkerRequest> captor = ArgumentCaptor.forClass(WorkerRequest.class);
        verify(workerService).stream(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("session-123");
    }
}
