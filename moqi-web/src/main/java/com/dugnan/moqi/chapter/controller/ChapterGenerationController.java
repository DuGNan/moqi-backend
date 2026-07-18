package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ChapterContent;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ContentSaved;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.CreateGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationCreated;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.LatestPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.SaveContentRequest;
import com.dugnan.moqi.chapter.service.ChapterGenerationService;
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

    /**
     * 创建章节生成控制器。
     *
     * @param chapterGenerationService 章节生成服务
     */
    public ChapterGenerationController(ChapterGenerationService chapterGenerationService) {
        this.chapterGenerationService = chapterGenerationService;
    }

    /**
     * 创建章节正文生成记录。
     *
     * @param chapterId 章节 ID
     * @param request 生成请求
     * @return 创建响应
     */
    @PostMapping("/{chapterId}/generations")
    public ApiResponse<GenerationCreated> createGeneration(
            @PathVariable Long chapterId,
            @RequestBody CreateGenerationRequest request) {
        return ApiResponse.success(chapterGenerationService.createGeneration(chapterId, request));
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
