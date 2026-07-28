package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefState;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefView;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConfirmBriefRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.CreateBriefDraftRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.DecisionSources;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConsensusTaskCreated;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConsensusTaskRequest;
import com.dugnan.moqi.chapter.service.ChapterConsensusService;
import com.dugnan.moqi.chapter.service.ChapterConsensusTaskService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 提供章节结构化共识草稿、确认和来源追溯 HTTP 接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class ChapterConsensusController {

    private final ChapterConsensusService consensusService;
    private final ChapterConsensusTaskService consensusTaskService;

    /**
     * 创建章节共识控制器。
     *
     * @param consensusService 章节共识服务
     */
    public ChapterConsensusController(ChapterConsensusService consensusService) {
        this(consensusService, null);
    }

    /**
     * 创建支持异步收束的章节共识控制器。
     *
     * @param consensusService 章节共识服务
     * @param consensusTaskService 共识任务服务
     */
    @Autowired
    public ChapterConsensusController(
            ChapterConsensusService consensusService,
            ChapterConsensusTaskService consensusTaskService) {
        this.consensusService = consensusService;
        this.consensusTaskService = consensusTaskService;
    }

    /**
     * 查询章节最新草稿和最新确认版本。
     *
     * @param chapterId 章节 ID
     * @return Brief 状态响应
     */
    @GetMapping("/{chapterId}/briefs/state")
    public ApiResponse<BriefState> state(@PathVariable Long chapterId) {
        return ApiResponse.success(consensusService.getState(chapterId));
    }

    /**
     * 追加一个结构化 Brief 草稿。
     *
     * @param chapterId 章节 ID
     * @param request 草稿请求
     * @return 新草稿响应
     */
    @PostMapping("/{chapterId}/briefs")
    public ApiResponse<BriefView> createDraft(
            @PathVariable Long chapterId,
            @RequestBody CreateBriefDraftRequest request) {
        return ApiResponse.success(consensusService.createDraft(chapterId, request));
    }

    /**
     * 创建异步共识收束任务。
     *
     * @param chapterId 章节 ID
     * @param request 任务请求
     * @return 已创建任务响应
     */
    @PostMapping("/{chapterId}/briefs/consensus-tasks")
    public ApiResponse<ConsensusTaskCreated> createConsensusTask(
            @PathVariable Long chapterId,
            @RequestBody ConsensusTaskRequest request) {
        if (consensusTaskService == null) {
            throw new IllegalStateException("共识任务服务不可用");
        }
        return ApiResponse.success(consensusTaskService.createTask(chapterId, request));
    }

    /**
     * 使用乐观条件确认 Brief。
     *
     * @param chapterId 章节 ID
     * @param briefId Brief ID
     * @param request 确认请求
     * @return 已确认 Brief 响应
     */
    @PostMapping("/{chapterId}/briefs/{briefId}/confirm")
    public ApiResponse<BriefView> confirm(
            @PathVariable Long chapterId,
            @PathVariable Long briefId,
            @RequestBody ConfirmBriefRequest request) {
        return ApiResponse.success(consensusService.confirm(chapterId, briefId, request));
    }

    /**
     * 查询某个待决引用的讨论消息。
     *
     * @param chapterId 章节 ID
     * @param briefId Brief ID
     * @param decisionKey 待决键
     * @return 来源消息响应
     */
    @GetMapping("/{chapterId}/briefs/{briefId}/decisions/{decisionKey}/sources")
    public ApiResponse<DecisionSources> decisionSources(
            @PathVariable Long chapterId,
            @PathVariable Long briefId,
            @PathVariable String decisionKey) {
        return ApiResponse.success(
                consensusService.getDecisionSources(chapterId, briefId, decisionKey));
    }
}
