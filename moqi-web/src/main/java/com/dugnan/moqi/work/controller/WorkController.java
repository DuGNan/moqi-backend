package com.dugnan.moqi.work.controller;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.work.dto.CreateChapterCommand;
import com.dugnan.moqi.work.dto.CreateWorkCommand;
import com.dugnan.moqi.work.dto.WorkChapterModels.*;
import com.dugnan.moqi.work.service.WorkChapterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/works")
public class WorkController {
    private final WorkChapterService service;
    public WorkController(WorkChapterService service) { this.service = service; }

    @GetMapping
    public ApiResponse<WorkList> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(service.listWorks(status, keyword, limit));
    }

    @PostMapping
    public ApiResponse<WorkSummary> create(@Valid @RequestBody CreateWorkRequest request) {
        return ApiResponse.success(service.createWork(new CreateWorkCommand(request.title())));
    }

    @GetMapping("/{workId}")
    public ApiResponse<WorkDetail> detail(@PathVariable Long workId) { return ApiResponse.success(service.getWork(workId)); }

    @GetMapping("/{workId}/chapters")
    public ApiResponse<ChapterList> chapters(@PathVariable Long workId,
            @RequestParam(required = false) String chapterType,
            @RequestParam(required = false) String workflowStatus,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.listChapters(workId, chapterType, workflowStatus, keyword));
    }

    @PostMapping("/{workId}/chapters")
    public ApiResponse<ChapterCreated> createChapter(@PathVariable Long workId,
            @Valid @RequestBody CreateChapterRequest request) {
        return ApiResponse.success(service.createChapter(workId, new CreateChapterCommand(request.title(), request.chapterType())));
    }

    public record CreateWorkRequest(@NotBlank(message = "标题不能为空") @Size(max = 200, message = "标题不能超过 200 个字符") String title) {}
    public record CreateChapterRequest(@NotBlank(message = "标题不能为空") @Size(max = 200, message = "标题不能超过 200 个字符") String title, String chapterType) {}
}
