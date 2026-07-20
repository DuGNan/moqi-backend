package com.dugnan.moqi.knowledge.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ConfirmSettingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ConfirmSettingResult;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.IgnoreSettingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.IgnoreSettingResult;
import com.dugnan.moqi.knowledge.service.KnowledgeService;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 提供设定候选确认与忽略 HTTP 接口。
 */
@RestController
@RequestMapping("/api/setting-candidates")
public class SettingCandidateController {

    private final KnowledgeService knowledgeService;

    /**
     * 创建设定候选控制器。
     *
     * @param knowledgeService 知识层服务
     */
    public SettingCandidateController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 确认设定候选。
     *
     * @param candidateId 候选 ID
     * @param request 确认请求
     * @return 确认结果响应
     */
    @PostMapping("/{candidateId}/confirm")
    public ApiResponse<ConfirmSettingResult> confirm(
            @PathVariable Long candidateId,
            @RequestBody ConfirmSettingRequest request) {
        return ApiResponse.success(knowledgeService.confirmSettingCandidate(candidateId, request));
    }

    /**
     * 忽略设定候选。
     *
     * @param candidateId 候选 ID
     * @param request 忽略请求
     * @return 忽略结果响应
     */
    @PostMapping("/{candidateId}/ignore")
    public ApiResponse<IgnoreSettingResult> ignore(
            @PathVariable Long candidateId,
            @RequestBody(required = false) IgnoreSettingRequest request) {
        return ApiResponse.success(knowledgeService.ignoreSettingCandidate(candidateId, request));
    }
}
