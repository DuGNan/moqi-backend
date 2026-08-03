package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceModels.PreferenceDetail;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceModels.PreferenceRequest;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 提供章节讨论回复深度偏好的读写接口。
 */
@RestController
@RequestMapping("/api/reply-policy/preferences")
public class ReplyPolicyPreferenceController {

    private final ReplyPolicyPreferenceService preferenceService;

    /**
     * 创建回复策略偏好控制器。
     *
     * @param preferenceService 回复偏好服务
     */
    public ReplyPolicyPreferenceController(ReplyPolicyPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    /**
     * 查询指定作用域的偏好。
     *
     * @param scopeType 作用域类型
     * @param scopeId 作用域 ID
     * @return 偏好详情
     */
    @GetMapping
    public ApiResponse<PreferenceDetail> get(
            @RequestParam String scopeType,
            @RequestParam(required = false) Long scopeId) {
        return ApiResponse.success(preferenceService.get(scopeType, scopeId));
    }

    /**
     * 保存指定作用域的偏好。
     *
     * @param request 保存请求
     * @return 保存结果
     */
    @PutMapping
    public ApiResponse<PreferenceDetail> save(@RequestBody PreferenceRequest request) {
        return ApiResponse.success(preferenceService.save(request));
    }
}
