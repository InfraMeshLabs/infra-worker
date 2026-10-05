package koboolean.example.controller;

import com.inframesh.node.dto.NodeHealthResponse;
import koboolean.example.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIRECT Worker regression check: OUTBOUND Health Reporting only pushes the same
 * {@code NodeHealthResponse} that infra-node's {@code HealthCheckController} already serves here
 * over HTTP - adding OUTBOUND must not change this endpoint's behavior. Runs with the default
 * (DIRECT) configuration, no OUTBOUND profile active.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectHealthEndpointTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Value("${infra.node.api-key}")
    private String apiKey;

    @MockitoBean
    private WorkerService workerService;

    @Test
    void health_withValidApiKey_returnsUpWithSystemInfo() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/health"))
                .header("X-Infra-Api-Key", apiKey)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        NodeHealthResponse body = JsonMapper.shared().readValue(response.body(), NodeHealthResponse.class);
        assertThat(body.status().name()).isEqualTo("UP");
        assertThat(body.system().cpu()).isNotNull();
        assertThat(body.system().memory()).isNotNull();
        assertThat(body.runtime().activeRequests()).isNotNull();
    }

    @Test
    void health_withoutApiKey_isUnauthorized() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/health"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
