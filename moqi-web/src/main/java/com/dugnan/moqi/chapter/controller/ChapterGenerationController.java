package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ChapterContent;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ContentSaved;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.LatestPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.SaveContentRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.CreateEvaluationRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationReportView;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RetryEvaluationRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RevisionCandidateView;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.CreateSceneGenerationRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.SceneGenerationCreated;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.service.ChapterGenerationService;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.SceneGenerationService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 提供章节生成 HTTP 接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class ChapterGenerationController {

    private final ChapterGenerationService chapterGenerationService;
    private final ChapterGenerationBriefService briefService;
    private final SceneGenerationService sceneGenerationService;
    private final GenerationEvaluationService evaluationService;

    /**
     * 创建章节生成控制器。
     *
     * @param chapterGenerationService 章节正文预览与采纳服务
     * @param sceneGenerationService 场景级生成工作流服务
     */
    public ChapterGenerationController(
            ChapterGenerationService chapterGenerationService,
            SceneGenerationService sceneGenerationService,
            GenerationEvaluationService evaluationService,
            ChapterGenerationBriefService briefService) {
        this.chapterGenerationService = chapterGenerationService;
        this.sceneGenerationService = sceneGenerationService;
        this.evaluationService = evaluationService;
        this.briefService = briefService;
    }

    /**
     * 创建章节正文生成记录。
     *
     * @param chapterId 章节 ID
     * @param request 生成请求
     * @return 创建响应
     */
    @PostMapping("/{chapterId}/generations")
    public ApiResponse<SceneGenerationCreated> createGeneration(
            @PathVariable Long chapterId,
            @RequestBody CreateSceneGenerationRequest request) {
        return ApiResponse.success(sceneGenerationService.create(chapterId, request));
    }

    /**
     * 编译当前或指定已发布场景规划的只读章节正文生成说明。
     *
     * @param chapterId 章节 ID
     * @param scenePlanNo 可选场景规划版本号
     * @return 人类可读说明及固定来源元数据
     */
    @GetMapping("/{chapterId}/generation-brief-preview")
    public ApiResponse<GenerationBriefPreview> previewGenerationBrief(
            @PathVariable Long chapterId,
            @RequestParam(required = false) Integer scenePlanNo) {
        return ApiResponse.success(briefService.preview(chapterId, scenePlanNo));
    }

    @PostMapping("/{chapterId}/generations/{generationId}/evaluation-reports")
    public ApiResponse<EvaluationReportView> createEvaluation(@PathVariable Long chapterId, @PathVariable Long generationId,
            @RequestBody CreateEvaluationRequest request) {
        return ApiResponse.success(evaluationService.create(chapterId, generationId, request));
    }

    @GetMapping("/{chapterId}/generations/{generationId}/evaluation-reports/latest")
    public ApiResponse<EvaluationReportView> latestEvaluation(@PathVariable Long chapterId, @PathVariable Long generationId,
            Long generationSceneId) {
        return ApiResponse.success(evaluationService.latest(chapterId, generationId, generationSceneId));
    }

    @GetMapping("/{chapterId}/generations/{generationId}/evaluation-reports/{reportId}")
    public ApiResponse<EvaluationReportView> evaluation(@PathVariable Long chapterId, @PathVariable Long generationId,
            @PathVariable Long reportId) {
        return ApiResponse.success(evaluationService.get(chapterId, generationId, reportId));
    }

    @PostMapping("/{chapterId}/generations/{generationId}/evaluation-reports/{reportId}/retry")
    public ApiResponse<AgentRunView> retryEvaluation(@PathVariable Long chapterId, @PathVariable Long generationId,
            @PathVariable Long reportId, @RequestBody RetryEvaluationRequest request) {
        return ApiResponse.success(evaluationService.retry(chapterId, generationId, reportId, request));
    }

    @PostMapping("/{chapterId}/generations/{generationId}/evaluation-reports/{reportId}/cancel")
    public ApiResponse<AgentRunView> cancelEvaluation(@PathVariable Long chapterId, @PathVariable Long generationId,
            @PathVariable Long reportId) {
        return ApiResponse.success(evaluationService.cancel(chapterId, generationId, reportId));
    }

    @GetMapping("/{chapterId}/generations/{generationId}/evaluation-reports/{reportId}/revision-candidate")
    public ApiResponse<RevisionCandidateView> revisionCandidate(@PathVariable Long chapterId, @PathVariable Long generationId,
            @PathVariable Long reportId) {
        return ApiResponse.success(evaluationService.revisionCandidate(chapterId, generationId, reportId));
    }

    /**
     * 查询章节最近的待处理预览。
     *
     * @param chapterId 章节 ID
     * @return 最近预览，暂无预览时关键字段为 null
     */
    @GetMapping("/{chapterId}/generations/latest-preview")
    public ApiResponse<LatestPreview> getLatestPreview(@PathVariable Long chapterId) {
        return ApiResponse.success(chapterGenerationService.getLatestPreview(chapterId));
    }

    /**
     * 查询章节当前正文。
     *
     * @param chapterId 章节 ID
     * @return 章节正文
     */
    @GetMapping("/{chapterId}/content")
    public ApiResponse<ChapterContent> getContent(@PathVariable Long chapterId) {
        return ApiResponse.success(chapterGenerationService.getContent(chapterId));
    }

    /**
     * 按版本保存章节正文。
     *
     * @param chapterId 章节 ID
     * @param request 正文保存请求
     * @return 保存结果
     */
    @PutMapping("/{chapterId}/content")
    public ApiResponse<ContentSaved> saveContent(
            @PathVariable Long chapterId,
            @RequestBody SaveContentRequest request) {
        return ApiResponse.success(chapterGenerationService.saveContent(chapterId, request));
    }
}
