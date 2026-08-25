package com.dugnan.moqi.chapter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.FormalProseView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateDetail;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseWorkspaceView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.QualitySummary;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.WorkspaceSelectionView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateAdoptionView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateBasisView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseComparisonView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ComparisonSide;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dugnan.moqi.chapter.service.ProseWorkspaceService;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 验证统一正文工作区的读取、选择和候选 CAS 保存路由。
 */
class ProseWorkspaceControllerTest {

    private ProseWorkspaceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ProseWorkspaceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProseWorkspaceController(service)).build();
    }

    @Test
    void readsWorkspaceWithoutCreatingAnyTask() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(service.getWorkspace(2L)).thenReturn(new ProseWorkspaceView(
                2L,
                new FormalProseView("formal:2", "正文", "hash", 3, 2, true, null, now),
                List.of(),
                new WorkspaceSelectionView("formal", "formal:2", 0, now),
                List.of()));

        mvc.perform(get("/api/chapters/2/prose-workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formal.objectId").value("formal:2"))
                .andExpect(jsonPath("$.data.selection.objectKind").value("formal"));
    }

    @Test
    void savesCandidateWithCasVersion() throws Exception {
        when(service.saveCandidate(eq(2L), eq(8L), any())).thenReturn(candidate());

        mvc.perform(put("/api/chapters/2/prose-candidates/8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"新候选\",\"baseVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(8))
                .andExpect(jsonPath("$.data.contentVersion").value(5));

        verify(service).saveCandidate(eq(2L), eq(8L), any());
    }

    @Test
    void exposesSafeQualityRetryFactsWithoutInternalRunIdentifiers() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ProseCandidateDetail candidate = new ProseCandidateDetail(
                2L, 8L, "candidate:8", 8L, null, "generation", "active", "unadopted",
                "新候选", 5, "hash", 3,
                new QualitySummary("failed", null, "hash", now,
                        31L, 41L, 2, true, "任务未能完成，请稍后重试"),
                now, now);
        when(service.getCandidate(2L, 8L)).thenReturn(candidate);

        mvc.perform(get("/api/chapters/2/prose-candidates/8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quality.generationId").value(31))
                .andExpect(jsonPath("$.data.quality.reportId").value(41))
                .andExpect(jsonPath("$.data.quality.currentAttempt").value(2))
                .andExpect(jsonPath("$.data.quality.retryable").value(true))
                .andExpect(jsonPath("$.data.quality.failureDescription")
                        .value("任务未能完成，请稍后重试"))
                .andExpect(jsonPath("$.data.quality.agentRunId").doesNotExist())
                .andExpect(jsonPath("$.data.quality.aiTaskId").doesNotExist());
    }

    @Test
    void exposesBasisComparisonAndUniqueAdoptionRoutes() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ObjectMapper objectMapper = new ObjectMapper();
        when(service.getCandidateBasis(2L, 8L)).thenReturn(new ProseCandidateBasisView(
                "complete", false, 5L, "source-hash", "source-hash",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createArrayNode(),
                objectMapper.createObjectNode(), objectMapper.createArrayNode(), objectMapper.createObjectNode()));
        ComparisonSide formal = new ComparisonSide("formal", "formal:2", "正文", 3, "formal-hash", 2,
                null, null, "formal", null, null, now);
        ComparisonSide candidate = new ComparisonSide("candidate", "candidate:8", "候选", 4, "hash", 2,
                8L, null, "generation", 5L, null, now);
        when(service.compare(2L, "formal:2", "candidate:8"))
                .thenReturn(new ProseComparisonView(formal, candidate));
        when(service.adoptCandidate(eq(2L), eq(8L), any())).thenReturn(new ProseCandidateAdoptionView(
                7L, 2L, 8L, 4, "hash", "direct_formal", "completed", 4,
                null, null, null, now));

        mvc.perform(get("/api/chapters/2/prose-candidates/8/basis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basisStatus").value("complete"));
        mvc.perform(get("/api/chapters/2/prose-comparison")
                        .param("leftObjectId", "formal:2").param("rightObjectId", "candidate:8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.right.objectId").value("candidate:8"));
        mvc.perform(post("/api/chapters/2/prose-candidates/8/adopt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidateVersion":4,"contentHash":"hash","expectedFormalVersion":3,
                                 "qualityReportId":9,"idempotencyKey":"adopt-1","userConfirmed":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adoptionMode").value("direct_formal"));
    }

    private ProseCandidateDetail candidate() {
        LocalDateTime now = LocalDateTime.now();
        return new ProseCandidateDetail(2L, 8L, "candidate:8", 8L, null, "generation",
                "active", "unadopted", "新候选", 5, "hash", 3,
                new QualitySummary("pending", null, "hash", null), now, now);
    }
}
