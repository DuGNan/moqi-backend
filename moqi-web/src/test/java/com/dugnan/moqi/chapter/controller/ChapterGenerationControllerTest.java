package com.dugnan.moqi.chapter.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ChapterContent;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ContentSaved;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationAccepted;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationCreated;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationDetail;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationRejected;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.LatestPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.LatestActiveGeneration;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefSourceRef;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.SceneGenerationCreated;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.service.ChapterGenerationService;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.SceneGenerationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 验证章节生成接口的 HTTP 协议。
 */
class ChapterGenerationControllerTest {

    private ChapterGenerationService chapterGenerationService;
    private SceneGenerationService sceneGenerationService;
    private GenerationEvaluationService evaluationService;
    private ChapterGenerationBriefService briefService;
    private MockMvc mvc;

    /**
     * 初始化章节生成控制器测试环境。
     */
    @BeforeEach
    void setUp() {
        chapterGenerationService = Mockito.mock(ChapterGenerationService.class);
        sceneGenerationService = Mockito.mock(SceneGenerationService.class);
        evaluationService = Mockito.mock(GenerationEvaluationService.class);
        briefService = Mockito.mock(ChapterGenerationBriefService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(
                        new ChapterGenerationController(
                                chapterGenerationService, sceneGenerationService, evaluationService, briefService),
                        new GenerationController(chapterGenerationService, sceneGenerationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 验证创建章节生成记录返回 draft 响应。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void createsChapterGeneration() throws Exception {
        when(sceneGenerationService.create(Mockito.eq(12L), Mockito.any()))
                .thenReturn(new SceneGenerationCreated(7001L, 9003L, 9004L, 1202L, "queued", null));

        mvc.perform(post("/api/chapters/12/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "selectionMode": "all",
                                  "idempotencyKey": "controller-test-45"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.generationId").value(7001))
                .andExpect(jsonPath("$.data.agentRunId").value(9004))
                .andExpect(jsonPath("$.data.generationStatus").value("queued"));
    }

    @Test
    void previewsTheHumanReadableGenerationBriefForASpecificPublishedPlan() throws Exception {
        when(briefService.preview(12L, 6)).thenReturn(new GenerationBriefPreview(
                2L, 12L, 41L, 6, "chapter-generation-brief-v1", "current",
                List.of(new GenerationBriefSourceRef("CHAPTER_OUTLINE", "21", "4:2")),
                "brief-hash", LocalDateTime.of(2026, 8, 14, 10, 0),
                "# Chapter Generation Brief\n\n## P0｜章节身份与任务"));

        mvc.perform(get("/api/chapters/12/generation-brief-preview").param("scenePlanNo", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.chapterPlanVersionId").value(41))
                .andExpect(jsonPath("$.data.scenePlanNo").value(6))
                .andExpect(jsonPath("$.data.templateVersion").value("chapter-generation-brief-v1"))
                .andExpect(jsonPath("$.data.sourceValidity").value("current"))
                .andExpect(jsonPath("$.data.sourceRefs[0].sourceType").value("CHAPTER_OUTLINE"))
                .andExpect(jsonPath("$.data.fingerprint").value("brief-hash"))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString(
                        "Chapter Generation Brief")));
    }

    /**
     * 验证生成详情与最近预览空态接口。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void getsGenerationDetailAndEmptyLatestPreview() throws Exception {
        when(chapterGenerationService.getGeneration(7001L)).thenReturn(new GenerationDetail(
                7001L,
                1L,
                12L,
                1202L,
                null,
                1201L,
                4,
                "preview",
                "full_draft",
                "about_3000",
                null,
                Map.of("goal", "隐藏房间回应林风"),
                "预览正文",
                4,
                9003L,
                9004L,
                9005L,
                "current",
                "scene_join_legacy",
                "not_applicable",
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        when(chapterGenerationService.getLatestPreview(12L))
                .thenReturn(new LatestPreview(null, 12L, null, null, null, null));

        mvc.perform(get("/api/generations/7001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outlineId").value(1201))
                .andExpect(jsonPath("$.data.chapterPlanVersionId").value(1202))
                .andExpect(jsonPath("$.data.agentRunId").value(9004))
                .andExpect(jsonPath("$.data.sourceSnapshotId").value(9005))
                .andExpect(jsonPath("$.data.basisSnapshot.goal").value("隐藏房间回应林风"));
        mvc.perform(get("/api/chapters/12/generations/latest-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.chapterId").value(12))
                .andExpect(jsonPath("$.data.generationStatus").value(org.hamcrest.Matchers.nullValue()));
    }

    /** 验证章节活动生成恢复接口公开步骤尝试元数据。 */
    @Test
    void getsLatestActiveGeneration() throws Exception {
        when(chapterGenerationService.getLatestActiveGeneration(12L))
                .thenReturn(new LatestActiveGeneration(
                        7004L, 12L, "failed", "whole_chapter_once", 9003L, 9004L,
                        "generate_chapter", 2, true, LocalDateTime.of(2026, 8, 15, 12, 0)));

        mvc.perform(get("/api/chapters/12/generations/latest-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationId").value(7004))
                .andExpect(jsonPath("$.data.contentAssemblyMode").value("whole_chapter_once"))
                .andExpect(jsonPath("$.data.currentAttempt").value(2))
                .andExpect(jsonPath("$.data.retryable").value(true));
    }

    /**
     * 验证采纳、拒绝和重新生成路径。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void routesGenerationActions() throws Exception {
        when(chapterGenerationService.acceptGeneration(Mockito.eq(7001L), Mockito.any()))
                .thenReturn(new GenerationAccepted(1L, 12L, 7001L, "accepted", 4, "co_creation", null));
        when(chapterGenerationService.rejectGeneration(Mockito.eq(7002L), Mockito.any()))
                .thenReturn(new GenerationRejected(7002L, "rejected", null));
        when(sceneGenerationService.regenerate(Mockito.eq(7001L), Mockito.any()))
                .thenReturn(new SceneGenerationCreated(7003L, 9006L, 1L, 12L, "queued", null));
        when(sceneGenerationService.retryGeneration(Mockito.eq(7004L), Mockito.any()))
                .thenReturn(new AgentRunView(9007L, "scene_novel_generation", "running", 1L, 12L,
                        9008L, "generate_chapter", 2L, null, null, null, null, null));

        mvc.perform(post("/api/generations/7001/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applyMode\":\"replace\",\"baseVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationStatus").value("accepted"));
        mvc.perform(post("/api/generations/7002/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"继续讨论\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationStatus").value("rejected"));
        mvc.perform(post("/api/generations/7001/regenerate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionMode\":\"rewrite_selected\",\"sceneKeys\":[\"scene-1\"],"
                                + "\"idempotencyKey\":\"rewrite-7001-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationId").value(7003));
        mvc.perform(post("/api/generations/7004/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAttempt\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStepKey").value("generate_chapter"));
    }

    /**
     * 验证章节正文读取与保存路径。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void getsAndSavesChapterContent() throws Exception {
        when(chapterGenerationService.getContent(12L))
                .thenReturn(new ChapterContent(1L, 12L, "章节", "旧正文", 3, 3, null));
        when(chapterGenerationService.saveContent(Mockito.eq(12L), Mockito.any()))
                .thenReturn(new ContentSaved(12L, true, 4, false, 3, null));

        mvc.perform(get("/api/chapters/12/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("旧正文"));
        mvc.perform(put("/api/chapters/12/content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"新正文\",\"baseVersion\":3,\"saveSource\":\"manual\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(true))
                .andExpect(jsonPath("$.data.version").value(4));
    }

    /**
     * 验证正文版本冲突映射为 409 并返回服务端状态。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void mapsContentVersionConflictTo409WithServerState() throws Exception {
        LocalDateTime savedAt = LocalDateTime.of(2026, 7, 18, 20, 30);
        when(chapterGenerationService.saveContent(Mockito.eq(12L), Mockito.any()))
                .thenThrow(new BusinessException(
                        ErrorCode.CHAPTER_VERSION_CONFLICT,
                        "章节正文已更新",
                        Map.of(
                                "serverContent", "服务端正文",
                                "version", 4,
                                "serverSavedAt", savedAt)));

        mvc.perform(put("/api/chapters/12/content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"新正文\",\"baseVersion\":3,\"saveSource\":\"auto_save\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAPTER_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.serverContent").value("服务端正文"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.serverSavedAt").exists());
    }
}
