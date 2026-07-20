package com.dugnan.moqi.knowledge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ChapterKeyEventList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ChapterSummaryDetail;
import com.dugnan.moqi.knowledge.service.KnowledgeService;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 提供章节摘要与关键事件只读 HTTP 接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class ChapterKnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 创建章节知识层控制器。
     *
     * @param knowledgeService 知识层服务
     */
    public ChapterKnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 查询章节摘要。
     *
     * @param chapterId 章节 ID
     * @return 摘要详情响应
     */
    @GetMapping("/{chapterId}/summary")
    public ApiResponse<ChapterSummaryDetail> summary(@PathVariable Long chapterId) {
        return ApiResponse.success(knowledgeService.getChapterSummary(chapterId));
    }

    /**
     * 查询章节关键事件。
     *
     * @param chapterId 章节 ID
     * @return 事件列表响应
     */
    @GetMapping("/{chapterId}/key-events")
    public ApiResponse<ChapterKeyEventList> keyEvents(@PathVariable Long chapterId) {
        return ApiResponse.success(knowledgeService.listChapterKeyEvents(chapterId));
    }
}
