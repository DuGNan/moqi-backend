/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供章节详情与打开聚合 HTTP 接口。
 */
package com.dugnan.moqi.work.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterDetail;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterOpen;
import com.dugnan.moqi.work.service.WorkChapterService;

@RestController
@RequestMapping("/api/chapters")
public class ChapterController {
    private final WorkChapterService service;

    public ChapterController(WorkChapterService service) {
        this.service = service;
    }

    @GetMapping("/{chapterId}")
    public ApiResponse<ChapterDetail> detail(@PathVariable Long chapterId) {
        return ApiResponse.success(service.getChapter(chapterId));
    }

    @PostMapping("/{chapterId}/open")
    public ApiResponse<ChapterOpen> open(@PathVariable Long chapterId,
            @RequestBody(required = false) OpenChapterRequest request) {
        return ApiResponse.success(service.openChapter(chapterId));
    }

    public record OpenChapterRequest(String source) {
    }
}
