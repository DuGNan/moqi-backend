package com.dugnan.moqi.knowledge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.CreateForeshadowingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ForeshadowingDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ForeshadowingList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingCandidateList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingList;
import com.dugnan.moqi.knowledge.service.KnowledgeService;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 提供作品级设定候选、正式设定与伏笔 HTTP 接口。
 */
@RestController
@RequestMapping("/api/works")
public class WorkKnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 创建作品知识层控制器。
     *
     * @param knowledgeService 知识层服务
     */
    public WorkKnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 查询作品设定候选。
     *
     * @param workId 作品 ID
     * @param chapterId 来源章节 ID
     * @param candidateStatus 候选状态
     * @param settingType 设定类型
     * @param keyword 关键字
     * @param page 页码
     * @param pageSize 每页数量
     * @return 候选列表响应
     */
    @GetMapping("/{workId}/setting-candidates")
    public ApiResponse<SettingCandidateList> settingCandidates(
            @PathVariable Long workId,
            @RequestParam(required = false) Long chapterId,
            @RequestParam(required = false) String candidateStatus,
            @RequestParam(required = false) String settingType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(knowledgeService.listSettingCandidates(
                workId, chapterId, candidateStatus, settingType, keyword, page, pageSize));
    }

    /**
     * 查询作品正式设定。
     *
     * @param workId 作品 ID
     * @param settingType 设定类型
     * @param entryStatus 设定状态
     * @param keyword 关键字
     * @param page 页码
     * @param pageSize 每页数量
     * @return 正式设定列表响应
     */
    @GetMapping("/{workId}/settings")
    public ApiResponse<SettingList> settings(
            @PathVariable Long workId,
            @RequestParam(required = false) String settingType,
            @RequestParam(required = false) String entryStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(knowledgeService.listSettings(
                workId, settingType, entryStatus, keyword, page, pageSize));
    }

    /**
     * 查询作品伏笔。
     *
     * @param workId 作品 ID
     * @param status 伏笔状态
     * @param sourceChapterId 来源章节 ID
     * @param payoffChapterId 回收章节 ID
     * @param page 页码
     * @param pageSize 每页数量
     * @return 伏笔列表响应
     */
    @GetMapping("/{workId}/foreshadowings")
    public ApiResponse<ForeshadowingList> foreshadowings(
            @PathVariable Long workId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long sourceChapterId,
            @RequestParam(required = false) Long payoffChapterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(knowledgeService.listForeshadowings(
                workId, status, sourceChapterId, payoffChapterId, page, pageSize));
    }

    /**
     * 创建作品伏笔。
     *
     * @param workId 作品 ID
     * @param request 创建请求
     * @return 伏笔详情响应
     */
    @PostMapping("/{workId}/foreshadowings")
    public ApiResponse<ForeshadowingDetail> createForeshadowing(
            @PathVariable Long workId,
            @RequestBody CreateForeshadowingRequest request) {
        return ApiResponse.success(knowledgeService.createForeshadowing(workId, request));
    }
}
