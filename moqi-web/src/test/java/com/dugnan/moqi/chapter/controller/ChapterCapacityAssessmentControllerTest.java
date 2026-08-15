package com.dugnan.moqi.chapter.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.capacity.ChapterCapacityAssessmentService;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityAssessmentView;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityResult;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证章节容量评估 HTTP 契约与领域错误映射。
 */
class ChapterCapacityAssessmentControllerTest {

    private ChapterCapacityAssessmentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ChapterCapacityAssessmentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChapterCapacityAssessmentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void createsAndReadsAPersistedAssessment() throws Exception {
        CapacityResult result = new CapacityResult("too_dense", 1800, 2500, List.of("事件过密"),
                List.of(), List.of(), List.of("scene-1：因果节点"), List.of("按因果边界拆章"),
                List.of("continue_long_chapter"), "model", null, false);
        CapacityAssessmentView view = new CapacityAssessmentView(8L, 2L, 12L, 31L, 4, 1500,
                "ready", result, "chapter-generation-brief-v1", "brief-hash", "input-hash",
                41L, 51L, 61L, null, null, 2, LocalDateTime.now(), LocalDateTime.now());
        when(service.create(Mockito.eq(12L), Mockito.any())).thenReturn(view);
        when(service.get(8L)).thenReturn(view);

        mvc.perform(post("/api/chapters/12/capacity-assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lengthPreset\":\"about_1500\",\"idempotencyKey\":\"capacity-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(8))
                .andExpect(jsonPath("$.data.result.status").value("too_dense"));
        mvc.perform(get("/api/chapter-capacity-assessments/8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inputFingerprint").value("input-hash"));
    }

    @Test
    void mapsMissingAssessmentToDomainError() throws Exception {
        when(service.get(99L)).thenThrow(new BusinessException(
                ErrorCode.CHAPTER_CAPACITY_ASSESSMENT_NOT_FOUND, "容量评估不存在"));

        mvc.perform(get("/api/chapter-capacity-assessments/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAPTER_CAPACITY_ASSESSMENT_NOT_FOUND"));
    }
}
