package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.AcceptGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationAccepted;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationDetail;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationRejected;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.RejectGenerationRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.GenerationSceneList;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.GenerationSceneView;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.RetrySceneRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.RetryGenerationRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.CreateSceneGenerationRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.SceneGenerationCreated;
import com.dugnan.moqi.chapter.service.ChapterGenerationService;
import com.dugnan.moqi.chapter.service.SceneGenerationService;
import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 提供生成资源查询及状态操作 HTTP 接口。
 */
@RestController
@RequestMapping("/api/generations")
public class GenerationController {

    private final ChapterGenerationService chapterGenerationService;
    private final SceneGenerationService sceneGenerationService;

    /**
     * 创建生成资源控制器。
     *
     * @param chapterGenerationService 章节预览与正文服务
     * @param sceneGenerationService 场景生成工作流服务
     */
    public GenerationController(
            ChapterGenerationService chapterGenerationService,
            SceneGenerationService sceneGenerationService) {
        this.chapterGenerationService = chapterGenerationService;
        this.sceneGenerationService = sceneGenerationService;
    }

    /**
     * 查询生成详情。
     *
     * @param generationId 生成记录 ID
     * @return 生成详情
     */
    @GetMapping("/{generationId}")
    public ApiResponse<GenerationDetail> getGeneration(@PathVariable Long generationId) {
        return ApiResponse.success(chapterGenerationService.getGeneration(generationId));
    }

    /**
     * 采纳生成预览。
     *
     * @param generationId 生成记录 ID
     * @param request 采纳请求
     * @return 采纳结果
     */
    @PostMapping("/{generationId}/accept")
    public ApiResponse<GenerationAccepted> acceptGeneration(
            @PathVariable Long generationId,
            @RequestBody AcceptGenerationRequest request) {
        return ApiResponse.success(chapterGenerationService.acceptGeneration(generationId, request));
    }

    /**
     * 拒绝生成预览。
     *
     * @param generationId 生成记录 ID
     * @param request 拒绝请求
     * @return 拒绝结果
     */
    @PostMapping("/{generationId}/reject")
    public ApiResponse<GenerationRejected> rejectGeneration(
            @PathVariable Long generationId,
            @RequestBody RejectGenerationRequest request) {
        return ApiResponse.success(chapterGenerationService.rejectGeneration(generationId, request));
    }

    /**
     * 基于原有依据重新生成。
     *
     * @param generationId 原生成记录 ID
     * @param request 重新生成请求
     * @return 新生成记录
     */
    @PostMapping("/{generationId}/regenerate")
    public ApiResponse<SceneGenerationCreated> regenerate(
            @PathVariable Long generationId,
            @RequestBody CreateSceneGenerationRequest request) {
        return ApiResponse.success(sceneGenerationService.regenerate(generationId, request));
    }

    @GetMapping("/{generationId}/scenes")
    public ApiResponse<GenerationSceneList> listScenes(@PathVariable Long generationId) {
        return ApiResponse.success(sceneGenerationService.listScenes(generationId));
    }

    @GetMapping("/{generationId}/scenes/{sceneId}")
    public ApiResponse<GenerationSceneView> getScene(
            @PathVariable Long generationId,
            @PathVariable Long sceneId) {
        return ApiResponse.success(sceneGenerationService.getScene(generationId, sceneId));
    }

    @PostMapping("/{generationId}/cancel")
    public ApiResponse<AgentRunView> cancel(@PathVariable Long generationId) {
        return ApiResponse.success(sceneGenerationService.cancel(generationId));
    }

    @PostMapping("/{generationId}/scenes/{sceneId}/retry")
    public ApiResponse<AgentRunView> retryScene(
            @PathVariable Long generationId,
            @PathVariable Long sceneId,
            @RequestBody RetrySceneRequest request) {
        return ApiResponse.success(sceneGenerationService.retryScene(generationId, sceneId, request));
    }

    /** 重试失败的整章一次生成步骤，不重新读取当前来源。 */
    @PostMapping("/{generationId}/retry")
    public ApiResponse<AgentRunView> retryGeneration(
            @PathVariable Long generationId,
            @RequestBody RetryGenerationRequest request) {
        return ApiResponse.success(sceneGenerationService.retryGeneration(generationId, request));
    }

    /** 重试失败的整章收束，不重新调用已完成的逐场景生成。 */
    @PostMapping("/{generationId}/cohere/retry")
    public ApiResponse<AgentRunView> retryCohesion(@PathVariable Long generationId) {
        return ApiResponse.success(sceneGenerationService.retryCohesion(generationId));
    }
}
