package koboolean.example.controller;

import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.ChatStreamResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.monitor.ActiveRequestCounter;
import koboolean.example.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * DIRECT transport entry point. DIRECT requests never pass through infra-node's OUTBOUND
 * lifecycle, so this is where they are counted in {@link ActiveRequestCounter} - from subscription
 * until the response terminates (complete, error or cancel / client disconnect). OUTBOUND requests
 * are counted by the SDK, which is why {@code WorkerService} itself never counts.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class WorkerController {

    private final WorkerService workerService;
    private final ActiveRequestCounter activeRequestCounter;

    @PostMapping("/invoke")
    public Mono<ChatResponse> invoke(@RequestBody WorkerRequest request) {
        return Mono.defer(() -> workerService.invoke(request))
                .doOnSubscribe(subscription -> activeRequestCounter.increment())
                .doFinally(signal -> activeRequestCounter.decrement());
    }

    @PostMapping("/stream")
    public Flux<ChatStreamResponse> stream(@RequestBody WorkerRequest request) {
        return Flux.defer(() -> workerService.stream(request))
                .doOnSubscribe(subscription -> activeRequestCounter.increment())
                .doFinally(signal -> activeRequestCounter.decrement());
    }

}
