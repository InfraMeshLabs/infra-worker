package koboolean.example.controller;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.ChatStreamResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.monitor.ActiveRequestCounter;
import koboolean.example.service.WorkerService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The DIRECT entry point counts a request in {@link ActiveRequestCounter} from subscription until
 * its response terminates - complete, error or cancel - and never before it is subscribed.
 */
class WorkerControllerActiveRequestsTest {

    private final WorkerService workerService = mock(WorkerService.class);
    private final ActiveRequestCounter counter = new ActiveRequestCounter();
    private final WorkerController controller = new WorkerController(workerService, counter);
    private final WorkerRequest request = new WorkerRequest(List.of(ChatMessage.user("hi")), null);

    @Test
    void invoke_isCountedFromSubscriptionUntilItCompletes() {
        Sinks.One<ChatResponse> result = Sinks.one();
        when(workerService.invoke(any(WorkerRequest.class))).thenReturn(result.asMono());

        Mono<ChatResponse> response = controller.invoke(request);
        assertThat(counter.get()).isZero();

        StepVerifier.create(response)
                .then(() -> assertThat(counter.get()).isEqualTo(1))
                .then(() -> result.tryEmitValue(new ChatResponse("done", null)))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(counter.get()).isZero();
    }

    @Test
    void invoke_isReleasedOnError() {
        Sinks.One<ChatResponse> result = Sinks.one();
        when(workerService.invoke(any(WorkerRequest.class))).thenReturn(result.asMono());

        StepVerifier.create(controller.invoke(request))
                .then(() -> assertThat(counter.get()).isEqualTo(1))
                .then(() -> result.tryEmitError(new IllegalStateException("model unavailable")))
                .verifyErrorMessage("model unavailable");
        assertThat(counter.get()).isZero();
    }

    @Test
    void invoke_isReleasedWhenTheServiceThrowsInsteadOfReturningAMono() {
        when(workerService.invoke(any(WorkerRequest.class))).thenThrow(new IllegalStateException("bad request"));

        StepVerifier.create(controller.invoke(request)).verifyErrorMessage("bad request");
        assertThat(counter.get()).isZero();
    }

    @Test
    void concurrentInvokes_areReleasedOneByOne() {
        Sinks.One<ChatResponse> a = Sinks.one();
        Sinks.One<ChatResponse> b = Sinks.one();
        when(workerService.invoke(any(WorkerRequest.class))).thenReturn(a.asMono()).thenReturn(b.asMono());

        controller.invoke(request).subscribe();
        controller.invoke(request).subscribe();
        assertThat(counter.get()).isEqualTo(2);

        a.tryEmitValue(new ChatResponse("a", null));
        assertThat(counter.get()).isEqualTo(1);

        b.tryEmitValue(new ChatResponse("b", null));
        assertThat(counter.get()).isZero();
    }

    @Test
    void stream_isCountedFromSubscriptionUntilItCompletes() {
        Sinks.Many<ChatStreamResponse> chunks = Sinks.many().unicast().onBackpressureBuffer();
        when(workerService.stream(any(WorkerRequest.class))).thenReturn(chunks.asFlux());

        Flux<ChatStreamResponse> response = controller.stream(request);
        assertThat(counter.get()).isZero();

        StepVerifier.create(response)
                .then(() -> chunks.tryEmitNext(new ChatStreamResponse("Hel")))
                .expectNextCount(1)
                .then(() -> assertThat(counter.get()).isEqualTo(1))
                .then(chunks::tryEmitComplete)
                .verifyComplete();
        assertThat(counter.get()).isZero();
    }

    @Test
    void stream_isReleasedOnError() {
        Sinks.Many<ChatStreamResponse> chunks = Sinks.many().unicast().onBackpressureBuffer();
        when(workerService.stream(any(WorkerRequest.class))).thenReturn(chunks.asFlux());

        StepVerifier.create(controller.stream(request))
                .then(() -> assertThat(counter.get()).isEqualTo(1))
                .then(() -> chunks.tryEmitError(new IllegalStateException("model unavailable")))
                .verifyErrorMessage("model unavailable");
        assertThat(counter.get()).isZero();
    }

    @Test
    void stream_isReleasedOnCancel() {
        Sinks.Many<ChatStreamResponse> chunks = Sinks.many().unicast().onBackpressureBuffer();
        when(workerService.stream(any(WorkerRequest.class))).thenReturn(chunks.asFlux());

        StepVerifier.create(controller.stream(request))
                .then(() -> chunks.tryEmitNext(new ChatStreamResponse("Hel")))
                .expectNextCount(1)
                .then(() -> assertThat(counter.get()).isEqualTo(1))
                .thenCancel()
                .verify();
        assertThat(counter.get()).isZero();
    }
}
