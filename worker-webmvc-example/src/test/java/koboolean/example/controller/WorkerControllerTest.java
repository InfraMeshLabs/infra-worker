package koboolean.example.controller;

import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.Usage;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.monitor.ActiveRequestCounter;
import koboolean.example.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkerController.class)
@Import(ActiveRequestCounter.class)
class WorkerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkerService workerService;

    @Test
    void invoke_withSessionId_passesSessionIdToService() throws Exception {
        when(workerService.invoke(any(WorkerRequest.class)))
                .thenReturn(new ChatResponse("hello", new Usage(1L, 1L, 2L)));

        mockMvc.perform(post("/api/v1/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "session-123",
                                  "messages": [{"role": "USER", "content": "hi"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("hello"));

        ArgumentCaptor<WorkerRequest> captor = ArgumentCaptor.forClass(WorkerRequest.class);
        verify(workerService).invoke(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("session-123");
    }

    @Test
    void invoke_withoutSessionId_stillInvokesNormally() throws Exception {
        when(workerService.invoke(any(WorkerRequest.class)))
                .thenReturn(new ChatResponse("hello", new Usage(1L, 1L, 2L)));

        mockMvc.perform(post("/api/v1/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{"role": "USER", "content": "hi"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("hello"));

        ArgumentCaptor<WorkerRequest> captor = ArgumentCaptor.forClass(WorkerRequest.class);
        verify(workerService).invoke(captor.capture());
        assertThat(captor.getValue().sessionId()).isNull();
    }
}
