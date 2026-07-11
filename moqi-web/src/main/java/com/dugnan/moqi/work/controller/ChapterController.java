package com.dugnan.moqi.work.controller;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterDetail;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterOpen;
import com.dugnan.moqi.work.service.WorkChapterService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chapters")
public class ChapterController {
    private final WorkChapterService service;
    public ChapterController(WorkChapterService service) { this.service = service; }

    @GetMapping("/{chapterId}")
    public ApiResponse<ChapterDetail> detail(@PathVariable Long chapterId) { return ApiResponse.success(service.getChapter(chapterId)); }

    @PostMapping("/{chapterId}/open")
    public ApiResponse<ChapterOpen> open(@PathVariable Long chapterId,
            @RequestBody(required = false) OpenChapterRequest request) {
        return ApiResponse.success(service.openChapter(chapterId));
    }

    public record OpenChapterRequest(String source) {}
}
