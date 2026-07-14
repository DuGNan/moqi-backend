package com.dugnan.moqi.work.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.work.dto.CreateChapterCommand;
import com.dugnan.moqi.work.dto.CreateWorkCommand;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterCreated;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterList;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkDetail;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkList;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkSummary;
import com.dugnan.moqi.work.service.WorkChapterService;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供作品及其章节的基础 HTTP 接口。
 */
@RestController
@RequestMapping("/api/works")
public class WorkController {
    private final WorkChapterService service;

    /**
     * 创建作品控制器。
     *
     * @param service 作品章节业务服务
     */
    public WorkController(WorkChapterService service) {
        this.service = service;
    }

    /**
     * 查询作品列表。
     *
     * @param status 作品状态
     * @param keyword 作品标题关键字
     * @param limit 返回数量上限
     * @return 作品列表响应
     */
    @GetMapping
    public ApiResponse<WorkList> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(service.listWorks(status, keyword, limit));
    }

    /**
     * 创建作品。
     *
     * @param request 创建作品请求
     * @return 创建后的作品摘要响应
     */
    @PostMapping
    public ApiResponse<WorkSummary> create(@Valid @RequestBody CreateWorkRequest request) {
        return ApiResponse.success(service.createWork(new CreateWorkCommand(request.title())));
    }

    /**
     * 查询作品详情。
     *
     * @param workId 作品 ID
     * @return 作品详情响应
     */
    @GetMapping("/{workId}")
    public ApiResponse<WorkDetail> detail(@PathVariable Long workId) {
        return ApiResponse.success(service.getWork(workId));
    }

    /**
     * 查询作品下的章节列表。
     *
     * @param workId 作品 ID
     * @param chapterType 章节类型
     * @param workflowStatus 工作流状态
     * @param keyword 章节标题关键字
     * @return 章节列表响应
     */
    @GetMapping("/{workId}/chapters")
    public ApiResponse<ChapterList> chapters(@PathVariable Long workId,
            @RequestParam(required = false) String chapterType,
            @RequestParam(required = false) String workflowStatus,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.listChapters(workId, chapterType, workflowStatus, keyword));
    }

    /**
     * 创建章节。
     *
     * @param workId 作品 ID
     * @param request 创建章节请求
     * @return 创建后的章节响应
     */
    @PostMapping("/{workId}/chapters")
    public ApiResponse<ChapterCreated> createChapter(@PathVariable Long workId,
            @Valid @RequestBody CreateChapterRequest request) {
        CreateChapterCommand command = new CreateChapterCommand(request.title(), request.chapterType());
        return ApiResponse.success(service.createChapter(workId, command));
    }

    public record CreateWorkRequest(
            @NotBlank(message = "标题不能为空")
            @Size(max = 200, message = "标题不能超过 200 个字符")
            String title) {
    }

    public record CreateChapterRequest(
            @NotBlank(message = "标题不能为空")
            @Size(max = 200, message = "标题不能超过 200 个字符")
            String title,
            String chapterType) {
    }
}
