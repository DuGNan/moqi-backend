package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;
import com.dugnan.moqi.chapter.service.ProseObjectConversationService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-08-26
 * @description 提供正式正文与候选对象独立会话的 HTTP 接口。
 */
@RestController
@RequestMapping("/api/chapters/{chapterId}/prose-objects/{objectId}/conversation")
public class ProseObjectConversationController {

    private final ProseObjectConversationService service;

    public ProseObjectConversationController(ProseObjectConversationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ConversationDetail> get(
            @PathVariable Long chapterId,
            @PathVariable String objectId) {
        return ApiResponse.success(service.get(chapterId, objectId));
    }

    @PostMapping
    public ApiResponse<ConversationDetail> createOrGet(
            @PathVariable Long chapterId,
            @PathVariable String objectId) {
        return ApiResponse.success(service.createOrGet(chapterId, objectId));
    }
}
