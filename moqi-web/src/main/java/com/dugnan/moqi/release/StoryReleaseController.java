package com.dugnan.moqi.release;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.release.StoryReleaseModels.AbandonRevisionRequest;
import com.dugnan.moqi.release.StoryReleaseModels.AbandonWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.BindEvaluationRequest;
import com.dugnan.moqi.release.StoryReleaseModels.CreateRevisionRequest;
import com.dugnan.moqi.release.StoryReleaseModels.CreateWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.PrepareWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.PublishWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.PutWorkspaceChapterRequest;
import com.dugnan.moqi.release.StoryReleaseModels.ReleaseDiff;
import com.dugnan.moqi.release.StoryReleaseModels.ReleaseView;
import com.dugnan.moqi.release.StoryReleaseModels.RevisionDiff;
import com.dugnan.moqi.release.StoryReleaseModels.RevisionView;
import com.dugnan.moqi.release.StoryReleaseModels.RollbackReleaseRequest;
import com.dugnan.moqi.release.StoryReleaseModels.WorkspaceView;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 暴露不可变正文 revision、修订工作区和 Story Release 用户确认接口。
 */
@RestController
@RequestMapping("/api/works/{workId}/story-revisions")
public class StoryReleaseController {
    private final StoryReleaseService storyReleaseService;

    public StoryReleaseController(StoryReleaseService storyReleaseService) {
        this.storyReleaseService = storyReleaseService;
    }

    @PostMapping("/chapters/{chapterId}/revisions")
    public ApiResponse<RevisionView> createRevision(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @RequestBody CreateRevisionRequest request) {
        return ApiResponse.success(storyReleaseService.createRevision(workId, chapterId, request));
    }

    @GetMapping("/chapters/{chapterId}/revisions")
    public ApiResponse<List<RevisionView>> revisions(
            @PathVariable Long workId,
            @PathVariable Long chapterId) {
        return ApiResponse.success(storyReleaseService.revisions(workId, chapterId));
    }

    @GetMapping("/chapters/{chapterId}/revisions/{revisionId}")
    public ApiResponse<RevisionView> revision(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @PathVariable Long revisionId) {
        return ApiResponse.success(storyReleaseService.revision(workId, chapterId, revisionId));
    }

    @GetMapping("/chapters/{chapterId}/revisions/{revisionId}/compare")
    public ApiResponse<RevisionDiff> compareRevisions(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @PathVariable Long revisionId,
            @RequestParam Long baseRevisionId) {
        return ApiResponse.success(storyReleaseService.compareRevisions(
                workId, chapterId, baseRevisionId, revisionId));
    }

    @PostMapping("/chapters/{chapterId}/revisions/{revisionId}/evaluation")
    public ApiResponse<RevisionView> bindEvaluation(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @PathVariable Long revisionId,
            @RequestBody BindEvaluationRequest request) {
        return ApiResponse.success(storyReleaseService.bindEvaluation(workId, chapterId, revisionId, request));
    }

    @PostMapping("/chapters/{chapterId}/revisions/{revisionId}/abandon")
    public ApiResponse<RevisionView> abandonRevision(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @PathVariable Long revisionId,
            @RequestBody AbandonRevisionRequest request) {
        return ApiResponse.success(storyReleaseService.abandonRevision(workId, chapterId, revisionId, request));
    }

    @PostMapping("/workspaces")
    public ApiResponse<WorkspaceView> createWorkspace(
            @PathVariable Long workId,
            @RequestBody CreateWorkspaceRequest request) {
        return ApiResponse.success(storyReleaseService.createWorkspace(workId, request));
    }

    @GetMapping("/workspaces/{workspaceId}")
    public ApiResponse<WorkspaceView> workspace(
            @PathVariable Long workId,
            @PathVariable Long workspaceId) {
        return ApiResponse.success(storyReleaseService.workspace(workId, workspaceId));
    }

    @PutMapping("/workspaces/{workspaceId}/chapters/{chapterId}")
    public ApiResponse<WorkspaceView> putWorkspaceChapter(
            @PathVariable Long workId,
            @PathVariable Long workspaceId,
            @PathVariable Long chapterId,
            @RequestBody PutWorkspaceChapterRequest request) {
        return ApiResponse.success(storyReleaseService.putWorkspaceChapter(
                workId, workspaceId, chapterId, request));
    }

    @PostMapping("/workspaces/{workspaceId}/prepare")
    public ApiResponse<WorkspaceView> prepareWorkspace(
            @PathVariable Long workId,
            @PathVariable Long workspaceId,
            @RequestBody PrepareWorkspaceRequest request) {
        return ApiResponse.success(storyReleaseService.prepareWorkspace(workId, workspaceId, request));
    }

    @PostMapping("/workspaces/{workspaceId}/publish")
    public ApiResponse<ReleaseView> publishWorkspace(
            @PathVariable Long workId,
            @PathVariable Long workspaceId,
            @RequestBody PublishWorkspaceRequest request) {
        return ApiResponse.success(storyReleaseService.publishWorkspace(workId, workspaceId, request));
    }

    @PostMapping("/workspaces/{workspaceId}/abandon")
    public ApiResponse<WorkspaceView> abandonWorkspace(
            @PathVariable Long workId,
            @PathVariable Long workspaceId,
            @RequestBody AbandonWorkspaceRequest request) {
        return ApiResponse.success(storyReleaseService.abandonWorkspace(workId, workspaceId, request));
    }

    @GetMapping("/releases")
    public ApiResponse<List<ReleaseView>> releases(@PathVariable Long workId) {
        return ApiResponse.success(storyReleaseService.releases(workId));
    }

    @GetMapping("/releases/{releaseId}")
    public ApiResponse<ReleaseView> release(
            @PathVariable Long workId,
            @PathVariable Long releaseId) {
        return ApiResponse.success(storyReleaseService.release(workId, releaseId));
    }

    @GetMapping("/releases/{releaseId}/compare")
    public ApiResponse<ReleaseDiff> compareReleases(
            @PathVariable Long workId,
            @PathVariable Long releaseId,
            @RequestParam Long baseReleaseId) {
        return ApiResponse.success(storyReleaseService.compareReleases(workId, baseReleaseId, releaseId));
    }

    @PostMapping("/releases/{releaseId}/rollback")
    public ApiResponse<ReleaseView> rollback(
            @PathVariable Long workId,
            @PathVariable Long releaseId,
            @RequestBody RollbackReleaseRequest request) {
        return ApiResponse.success(storyReleaseService.rollback(workId, releaseId, request));
    }
}
