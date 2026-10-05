package koboolean.example.controller;

import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.monitor.ActiveRequestCounter;
import koboolean.example.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DIRECT transport entry point. DIRECT requests never pass through infra-node's OUTBOUND
 * lifecycle, so this is where they are counted in {@link ActiveRequestCounter}. OUTBOUND requests
 * are counted by the SDK, which is why {@code WorkerService} itself never counts.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class WorkerController {

    private final WorkerService workerService;
    private final ActiveRequestCounter activeRequestCounter;

    @PostMapping("/invoke")
    public ChatResponse invoke(@RequestBody WorkerRequest request) {
        activeRequestCounter.increment();
        try {
            return workerService.invoke(request);
        } finally {
            activeRequestCounter.decrement();
        }
    }

}
