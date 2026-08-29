package com.dugnan.moqi.work.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.work.dto.CreateChapterCommand;
import com.dugnan.moqi.work.dto.CreateWorkCommand;
import com.dugnan.moqi.work.dto.UpdateWorkCommand;
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
    private final WorkChapterService workChapterService;

    /**
     * 创建作品控制器。
     *
     * @param workChapterService 作品章节业务服务
     */
    public WorkController(WorkChapterService workChapterService) {
        this.workChapterService = workChapterService;
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
        return ApiResponse.success(workChapterService.listWorks(status, keyword, limit));
    }

    /**
     * 创建作品。
     *
     * @param request 创建作品请求
     * @return 创建后的作品摘要响应
     */
    @PostMapping
    public ApiResponse<WorkSummary> create(@Valid @RequestBody CreateWorkRequest request) {
        return ApiResponse.success(workChapterService.createWork(new CreateWorkCommand(request.title())));
    }

    /**
     * 查询作品详情。
     *
     * @param workId 作品 ID
     * @return 作品详情响应
     */
    @GetMapping("/{workId}")
    public ApiResponse<WorkDetail> detail(@PathVariable Long workId) {
        return ApiResponse.success(workChapterService.getWork(workId));
    }

    /** 修改作品标题。 */
    @PutMapping("/{workId}")
    public ApiResponse<WorkDetail> update(
            @PathVariable Long workId,
            @Valid @RequestBody UpdateWorkRequest request) {
        return ApiResponse.success(
                workChapterService.updateWork(workId, new UpdateWorkCommand(request.title(), request.baseVersion())));
    }

    /** 逻辑删除作品及其未删除章节。 */
    @DeleteMapping("/{workId}")
    public ApiResponse<Void> delete(
            @PathVariable Long workId,
            @RequestParam Integer baseVersion) {
        workChapterService.deleteWork(workId, baseVersion);
        return ApiResponse.success(null);
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
        return ApiResponse.success(workChapterService.listChapters(workId, chapterType, workflowStatus, keyword));
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
        return ApiResponse.success(workChapterService.createChapter(workId, command));
    }

    public record CreateWorkRequest(
            @NotBlank(message = "标题不能为空")
            @Size(max = 200, message = "标题不能超过 200 个字符")
            String title) {
    }

    public record CreateChapterRequest(
            @Size(max = 200, message = "标题不能超过 200 个字符")
            String title,
            String chapterType) {
    }

    public record UpdateWorkRequest(
            @NotBlank(message = "标题不能为空")
            String title,
            @NotNull(message = "baseVersion 不能为空")
            @PositiveOrZero(message = "baseVersion 必须为非负整数")
            Integer baseVersion) {
    }
}
