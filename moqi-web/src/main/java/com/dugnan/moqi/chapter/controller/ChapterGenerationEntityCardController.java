package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.dto.ChapterGenerationEntityCardModels.EntityCardPreview;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 提供不调用模型且不修改权威资产的章节生成实体卡预览接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class ChapterGenerationEntityCardController {

    private final ChapterGenerationBriefService briefService;

    public ChapterGenerationEntityCardController(ChapterGenerationBriefService briefService) {
        this.briefService = briefService;
    }

    @GetMapping("/{chapterId}/generation-entity-cards-preview")
    public ApiResponse<EntityCardPreview> preview(
            @PathVariable Long chapterId,
            @RequestParam(required = false) Integer scenePlanNo) {
        return ApiResponse.success(briefService.previewEntityCards(chapterId, scenePlanNo));
    }
}
