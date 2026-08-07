package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.ExperimentList;
import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.ExperimentView;
import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.RunExperimentRequest;
import com.dugnan.moqi.chapter.service.ChapterGenerationExperimentService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 提供与正式正文和候选隔离的章节生成策略实验接口。
 */
@RestController
@RequestMapping("/api/chapters/{chapterId}/generation-experiments")
public class ChapterGenerationExperimentController {

    private final ChapterGenerationExperimentService experimentService;

    public ChapterGenerationExperimentController(
            ChapterGenerationExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping
    public ApiResponse<ExperimentView> run(
            @PathVariable Long chapterId,
            @RequestBody RunExperimentRequest request) {
        return ApiResponse.success(experimentService.run(chapterId, request));
    }

    @GetMapping
    public ApiResponse<ExperimentList> list(
            @PathVariable Long chapterId,
            @RequestParam String experimentGroupKey) {
        return ApiResponse.success(experimentService.list(chapterId, experimentGroupKey));
    }
}
