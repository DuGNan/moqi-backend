package com.dugnan.moqi.work.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterDetail;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterOpen;
import com.dugnan.moqi.work.dto.UpdateChapterCommand;
import com.dugnan.moqi.work.service.WorkChapterService;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供章节详情与打开聚合 HTTP 接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class ChapterController {
    private final WorkChapterService workChapterService;

    /**
     * 创建章节控制器。
     *
     * @param workChapterService 作品章节业务服务
     */
    public ChapterController(WorkChapterService workChapterService) {
        this.workChapterService = workChapterService;
    }

    /**
     * 查询章节详情。
     *
     * @param chapterId 章节 ID
     * @return 章节详情响应
     */
    @GetMapping("/{chapterId}")
    public ApiResponse<ChapterDetail> detail(@PathVariable Long chapterId) {
        return ApiResponse.success(workChapterService.getChapter(chapterId));
    }

    /** 修改章节标题。 */
    @PutMapping("/{chapterId}")
    public ApiResponse<ChapterDetail> update(
            @PathVariable Long chapterId,
            @Valid @RequestBody UpdateChapterRequest request) {
        return ApiResponse.success(workChapterService.updateChapter(
                chapterId,
                new UpdateChapterCommand(request.title(), request.baseVersion())));
    }

    /** 逻辑删除章节。 */
    @DeleteMapping("/{chapterId}")
    public ApiResponse<Void> delete(
            @PathVariable Long chapterId,
            @RequestParam Integer baseVersion) {
        workChapterService.deleteChapter(chapterId, baseVersion);
        return ApiResponse.success(null);
    }

    /**
     * 获取章节打开时的默认工作区建议。
     *
     * @param chapterId 章节 ID
     * @param request 打开请求，可为空
     * @return 章节打开建议响应
     */
    @PostMapping("/{chapterId}/open")
    public ApiResponse<ChapterOpen> open(@PathVariable Long chapterId,
            @RequestBody(required = false) OpenChapterRequest request) {
        return ApiResponse.success(workChapterService.openChapter(chapterId));
    }

    public record OpenChapterRequest(String source) {
    }

    public record UpdateChapterRequest(
            @NotBlank(message = "标题不能为空")
            String title,
            @NotNull(message = "baseVersion 不能为空")
            @PositiveOrZero(message = "baseVersion 必须为非负整数")
            Integer baseVersion) {
    }
}
