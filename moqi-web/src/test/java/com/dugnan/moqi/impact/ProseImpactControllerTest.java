package com.dugnan.moqi.impact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportResult;
import com.dugnan.moqi.impact.ProseImpactModels.ReportView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;

class ProseImpactControllerTest {
    @Test
    void exposesCreateLatestDetailAndRetryRoutes() throws Exception {
        ProseImpactService service = mock(ProseImpactService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProseImpactController(service)).build();
        ReportView report = new ReportView(20L, 1L, 2L, 10L, 5L, 6L, 9L, 51L, null,
                "fingerprint", "source-graph", "v1", "ready", "local", false, "局部影响", null,
                List.of(), List.of(), 2, null, null);
        when(service.create(any(), any(), any(), any())).thenReturn(new CreateReportResult(report, null));
        when(service.latest(1L, 2L, 6L)).thenReturn(report);
        when(service.detail(1L, 2L, 6L, 20L)).thenReturn(report);
        when(service.retry(any(), any(), any(), any(), any())).thenReturn(new AgentRunView(51L,
                ProseImpactServiceImpl.WORKFLOW_TYPE, "queued", 1L, 2L, null,
                ProseImpactServiceImpl.ANALYZE_STEP, 1L, null, null, null, null, null));

        mvc.perform(post("/api/works/1/story-revisions/chapters/2/revisions/6/impact-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceId\":10,\"baselineRevisionId\":5,\"idempotencyKey\":\"impact-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.report.id").value(20));
        mvc.perform(get("/api/works/1/story-revisions/chapters/2/revisions/6/impact-reports/latest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.impactScope").value("local"));
        mvc.perform(get("/api/works/1/story-revisions/chapters/2/revisions/6/impact-reports/20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.targetRevisionId").value(6));
        mvc.perform(post("/api/works/1/story-revisions/chapters/2/revisions/6/impact-reports/20/retry")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedAttempt\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentStepKey").value("analyze_impact"));
    }
}
