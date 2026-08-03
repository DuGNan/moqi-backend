package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.CreateOutlineCandidateRequest;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateConfirmation;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateCreated;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateDetail;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.RefreshOutlineRequest;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.UpdateOutlineCandidateRequest;
import com.dugnan.moqi.chapter.service.OutlineCandidateService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 提供章节大纲调整候选的创建、查询、放弃和确认 HTTP 接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class OutlineCandidateController {

    private final OutlineCandidateService candidateService;

    /**
     * 创建候选控制器。
     *
     * @param candidateService 大纲候选服务
     */
    public OutlineCandidateController(OutlineCandidateService candidateService) {
        this.candidateService = candidateService;
    }

    /**
     * 创建大纲调整候选任务。
     *
     * @param chapterId 章节 ID
     * @param request 候选任务请求
     * @return 已创建候选和任务引用
     */
    @PostMapping("/{chapterId}/outline/candidates")
    public ApiResponse<OutlineCandidateCreated> create(
            @PathVariable Long chapterId,
            @RequestBody CreateOutlineCandidateRequest request) {
        return ApiResponse.success(candidateService.create(chapterId, request));
    }

    /**
     * 兼容旧刷新路径，但只创建候选而不修改正式大纲。
     *
     * @param chapterId 章节 ID
     * @param request 旧刷新请求
     * @return 已创建候选和任务引用
     */
    @PostMapping("/{chapterId}/outline/refresh")
    public ApiResponse<OutlineCandidateCreated> refresh(
            @PathVariable Long chapterId,
            @RequestBody RefreshOutlineRequest request) {
        return ApiResponse.success(candidateService.create(chapterId, new CreateOutlineCandidateRequest(
                request == null ? null : request.conversationId(),
                request == null ? null : request.briefId(),
                request == null ? null : request.baseRevision(),
                request == null ? null : request.instruction(),
                "adjustment",
                null)));
    }

    /**
     * 查询章节最新候选。
     *
     * @param chapterId 章节 ID
     * @return 最新候选，不存在时 data 为 null
     */
    @GetMapping("/{chapterId}/outline/candidates/latest")
    public ApiResponse<OutlineCandidateDetail> latest(@PathVariable Long chapterId) {
        return ApiResponse.success(candidateService.getLatest(chapterId));
    }

    /**
     * 查询指定候选。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @return 候选详情
     */
    @GetMapping("/{chapterId}/outline/candidates/{candidateId}")
    public ApiResponse<OutlineCandidateDetail> detail(
            @PathVariable Long chapterId,
            @PathVariable Long candidateId) {
        return ApiResponse.success(candidateService.get(chapterId, candidateId));
    }

    /**
     * 保存用户编辑后的候选内容。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param request 候选内容和基础版本
     * @return 更新后的候选
     */
    @PutMapping("/{chapterId}/outline/candidates/{candidateId}")
    public ApiResponse<OutlineCandidateDetail> update(
            @PathVariable Long chapterId,
            @PathVariable Long candidateId,
            @RequestBody UpdateOutlineCandidateRequest request) {
        return ApiResponse.success(candidateService.update(chapterId, candidateId, request));
    }

    /**
     * 放弃已就绪候选。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @return 更新后的候选
     */
    @PostMapping("/{chapterId}/outline/candidates/{candidateId}/abandon")
    public ApiResponse<OutlineCandidateDetail> abandon(
            @PathVariable Long chapterId,
            @PathVariable Long candidateId) {
        return ApiResponse.success(candidateService.abandon(chapterId, candidateId));
    }

    /**
     * 确认候选并以条件更新写入下一版正式大纲。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @return 候选与正式大纲结果
     */
    @PostMapping("/{chapterId}/outline/candidates/{candidateId}/confirm")
    public ApiResponse<OutlineCandidateConfirmation> confirm(
            @PathVariable Long chapterId,
            @PathVariable Long candidateId) {
        return ApiResponse.success(candidateService.confirm(chapterId, candidateId));
    }
}
