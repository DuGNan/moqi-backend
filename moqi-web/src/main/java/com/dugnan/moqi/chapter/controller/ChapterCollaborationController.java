package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.BriefDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.BriefRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineRequest;
import com.dugnan.moqi.chapter.service.ChapterCollaborationService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-07-16
 * @description 提供章节级共创会话、简报和大纲 HTTP 接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class ChapterCollaborationController {

    private final ChapterCollaborationService chapterCollaborationService;

    /**
     * 创建章节共创控制器。
     *
     * @param chapterCollaborationService 章节共创服务
     */
    public ChapterCollaborationController(ChapterCollaborationService chapterCollaborationService) {
        this.chapterCollaborationService = chapterCollaborationService;
    }

    /**
     * 查询章节活动会话。
     *
     * @param chapterId 章节 ID
     * @return 会话详情响应
     */
    @GetMapping("/{chapterId}/conversation")
    public ApiResponse<ConversationDetail> conversation(@PathVariable Long chapterId) {
        return ApiResponse.success(chapterCollaborationService.getConversation(chapterId));
    }

    /**
     * 创建或复用章节活动会话。
     *
     * @param chapterId 章节 ID
     * @return 会话详情响应
     */
    @PostMapping("/{chapterId}/conversation")
    public ApiResponse<ConversationDetail> createConversation(@PathVariable Long chapterId) {
        return ApiResponse.success(chapterCollaborationService.createOrGetConversation(chapterId));
    }

    /**
     * 查询章节最新 brief。
     *
     * @param chapterId 章节 ID
     * @return brief 详情响应
     */
    @GetMapping("/{chapterId}/briefs/latest")
    public ApiResponse<BriefDetail> latestBrief(@PathVariable Long chapterId) {
        return ApiResponse.success(chapterCollaborationService.getLatestBrief(chapterId));
    }

    /**
     * 保存章节最新 brief。
     *
     * @param chapterId 章节 ID
     * @param request brief 请求
     * @return brief 详情响应
     */
    @PutMapping("/{chapterId}/briefs/latest")
    public ApiResponse<BriefDetail> saveLatestBrief(
            @PathVariable Long chapterId,
            @RequestBody BriefRequest request) {
        return ApiResponse.success(chapterCollaborationService.saveLatestBrief(chapterId, request));
    }

    /**
     * 查询章节大纲。
     *
     * @param chapterId 章节 ID
     * @return 大纲详情响应
     */
    @GetMapping("/{chapterId}/outline")
    public ApiResponse<OutlineDetail> outline(@PathVariable Long chapterId) {
        return ApiResponse.success(chapterCollaborationService.getOutline(chapterId));
    }

    /**
     * 保存章节大纲。
     *
     * @param chapterId 章节 ID
     * @param request 大纲请求
     * @return 大纲详情响应
     */
    @PutMapping("/{chapterId}/outline")
    public ApiResponse<OutlineDetail> saveOutline(
            @PathVariable Long chapterId,
            @RequestBody OutlineRequest request) {
        return ApiResponse.success(chapterCollaborationService.saveOutline(chapterId, request));
    }

    /**
     * 刷新章节大纲。
     *
     * @param chapterId 章节 ID
     * @return 大纲详情响应
     */
    @PostMapping("/{chapterId}/outline/refresh")
    public ApiResponse<OutlineDetail> refreshOutline(@PathVariable Long chapterId) {
        return ApiResponse.success(chapterCollaborationService.refreshOutline(chapterId));
    }
}
