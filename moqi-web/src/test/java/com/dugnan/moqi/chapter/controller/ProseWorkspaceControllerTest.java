package com.dugnan.moqi.chapter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    private ProseCandidateDetail candidate() {
        LocalDateTime now = LocalDateTime.now();
        return new ProseCandidateDetail(2L, 8L, "candidate:8", 8L, null, "generation",
                "active", "unadopted", "新候选", 5, "hash", 3,
                new QualitySummary("pending", null, "hash", null), now, now);
    }
}
