package com.dugnan.moqi.chapter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.View;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceService;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证选区协助独立 HTTP 路由及候选操作委派。
 */
class SelectionAssistanceControllerTest {

    private SelectionAssistanceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SelectionAssistanceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SelectionAssistanceController(service)).build();
    }

    @Test
    void createsSelectionAssistance() throws Exception {
        when(service.create(eq(2L), any())).thenReturn(view());

        mvc.perform(post("/api/chapters/2/selection-assistance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":3,"contentHash":"hash","selectionStart":0,"selectionEnd":2,
                                 "selectedText":"原文","operation":"rewrite","instruction":"凝练",
                                 "idempotencyKey":"k1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.operation").value("rewrite"));
    }

    @Test
    void reloadsPersistedCandidateAndAcceptsExplicitly() throws Exception {
        when(service.get(9L)).thenReturn(view());
        when(service.accept(eq(9L), any())).thenReturn(view());

        mvc.perform(get("/api/selection-assistance/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ready"));
        mvc.perform(post("/api/selection-assistance/9/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":3,\"contentHash\":\"hash\"}"))
                .andExpect(status().isOk());
        verify(service).accept(eq(9L), any());
    }

    @Test
    void delegatesRejectContinueCancelAndFailedRetryRoutes() throws Exception {
        AgentRunView run = new AgentRunView(7L, "chapter_selection_assistance_v1", "queued",
                1L, 2L, 8L, "generate_candidate", null, null, null, null, null, null);
        when(service.reject(9L)).thenReturn(view());
        when(service.continueFrom(eq(9L), any())).thenReturn(view());
        when(service.cancel(9L)).thenReturn(run);
        when(service.retry(eq(9L), any())).thenReturn(run);

        mvc.perform(post("/api/selection-assistance/9/reject"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/selection-assistance/9/continue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"再压缩\",\"idempotencyKey\":\"child-1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/selection-assistance/9/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runStatus").value("queued"));
        mvc.perform(post("/api/selection-assistance/9/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAttempt\":2}"))
                .andExpect(status().isOk());

        verify(service).reject(9L);
        verify(service).continueFrom(eq(9L), any());
        verify(service).cancel(9L);
        verify(service).retry(eq(9L), any());
    }

    private View view() {
        return new View(9L, 1L, 2L, null, 8L, 7L, "rewrite", "ready", 3, "hash", 0, 2,
                "原文", "凝练", "brief-v1", "brief-hash", "input-hash", "候选", null, "safe",
                List.of(), true, null, null, null, null, 1, null, null);
    }
}
