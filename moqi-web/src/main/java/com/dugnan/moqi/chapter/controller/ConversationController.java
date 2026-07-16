package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageCreated;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageList;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.SendMessageRequest;
import com.dugnan.moqi.chapter.service.ChapterCollaborationService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-07-16
 * @description 提供会话消息 HTTP 接口。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ChapterCollaborationService service;

    /**
     * 创建会话控制器。
     *
     * @param service 章节共创服务
     */
    public ConversationController(ChapterCollaborationService service) {
        this.service = service;
    }

    /**
     * 查询会话消息列表。
     *
     * @param conversationId 会话 ID
     * @return 消息列表响应
     */
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<MessageList> messages(@PathVariable Long conversationId) {
        return ApiResponse.success(service.listMessages(conversationId));
    }

    /**
     * 发送会话消息。
     *
     * @param conversationId 会话 ID
     * @param request 发送消息请求
     * @return 已创建消息响应
     */
    @PostMapping("/{conversationId}/messages")
    public ApiResponse<MessageCreated> sendMessage(
            @PathVariable Long conversationId,
            @RequestBody SendMessageRequest request) {
        return ApiResponse.success(service.sendMessage(conversationId, request));
    }
}
