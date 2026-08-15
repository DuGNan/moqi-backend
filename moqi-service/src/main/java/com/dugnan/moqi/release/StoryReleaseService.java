package com.dugnan.moqi.release;

import java.util.List;

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
 * @description 管理正文 revision、作品修订工作区与 Story Release 原子切换。
 */
public interface StoryReleaseService {
    /**
     * 创建不可变正文 revision。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param request 创建请求
     * @return 正文 revision
     */
    RevisionView createRevision(Long workId, Long chapterId, CreateRevisionRequest request);

    /**
     * 查询单个正文 revision。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param revisionId revision ID
     * @return 正文 revision
     */
    RevisionView revision(Long workId, Long chapterId, Long revisionId);

    /**
     * 查询章节的正文 revision 列表。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @return 正文 revision 列表
     */
    List<RevisionView> revisions(Long workId, Long chapterId);

    /**
     * 对比两个正文 revision。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param baseRevisionId 基线 revision ID
     * @param targetRevisionId 目标 revision ID
     * @return revision 对比结果
     */
    RevisionDiff compareRevisions(Long workId, Long chapterId, Long baseRevisionId, Long targetRevisionId);

    /**
     * 将质量评价报告绑定到正文 revision。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param revisionId revision ID
     * @param request 评价绑定请求
     * @return 更新后的正文 revision
     */
    RevisionView bindEvaluation(Long workId, Long chapterId, Long revisionId, BindEvaluationRequest request);

    /**
     * 放弃尚未发布的正文 revision。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param revisionId revision ID
     * @param request 放弃请求
     * @return 已放弃的正文 revision
     */
    RevisionView abandonRevision(Long workId, Long chapterId, Long revisionId, AbandonRevisionRequest request);

    /**
     * 创建作品修订工作区。
     *
     * @param workId 作品 ID
     * @param request 创建请求
     * @return 作品修订工作区
     */
    WorkspaceView createWorkspace(Long workId, CreateWorkspaceRequest request);

    /**
     * 查询作品修订工作区。
     *
     * @param workId 作品 ID
     * @param workspaceId 工作区 ID
     * @return 作品修订工作区
     */
    WorkspaceView workspace(Long workId, Long workspaceId);

    /**
     * 在作品修订工作区中选择章节 revision。
     *
     * @param workId 作品 ID
     * @param workspaceId 工作区 ID
     * @param chapterId 章节 ID
     * @param request 章节 revision 选择请求
     * @return 更新后的作品修订工作区
     */
    WorkspaceView putWorkspaceChapter(
            Long workId,
            Long workspaceId,
            Long chapterId,
            PutWorkspaceChapterRequest request);

    /**
     * 检查工作区是否具备发布条件。
     *
     * @param workId 作品 ID
     * @param workspaceId 工作区 ID
     * @param request 准备请求
     * @return 已更新阻塞项的工作区
     */
    WorkspaceView prepareWorkspace(Long workId, Long workspaceId, PrepareWorkspaceRequest request);

    /**
     * 经用户确认后原子发布工作区。
     *
     * @param workId 作品 ID
     * @param workspaceId 工作区 ID
     * @param request 发布请求
     * @return 当前 Story Release
     */
    ReleaseView publishWorkspace(Long workId, Long workspaceId, PublishWorkspaceRequest request);

    /**
     * 放弃尚未发布的作品修订工作区。
     *
     * @param workId 作品 ID
     * @param workspaceId 工作区 ID
     * @param request 放弃请求
     * @return 已放弃的工作区
     */
    WorkspaceView abandonWorkspace(Long workId, Long workspaceId, AbandonWorkspaceRequest request);

    /**
     * 查询单个 Story Release。
     *
     * @param workId 作品 ID
     * @param releaseId Story Release ID
     * @return Story Release
     */
    ReleaseView release(Long workId, Long releaseId);

    /**
     * 查询作品的 Story Release 列表。
     *
     * @param workId 作品 ID
     * @return Story Release 列表
     */
    List<ReleaseView> releases(Long workId);

    /**
     * 对比两个 Story Release。
     *
     * @param workId 作品 ID
     * @param baseReleaseId 基线 Story Release ID
     * @param targetReleaseId 目标 Story Release ID
     * @return Story Release 对比结果
     */
    ReleaseDiff compareReleases(Long workId, Long baseReleaseId, Long targetReleaseId);

    /**
     * 经用户确认后原子回退到目标 Story Release。
     *
     * @param workId 作品 ID
     * @param targetReleaseId 目标 Story Release ID
     * @param request 回退请求
     * @return 新建的当前 Story Release
     */
    ReleaseView rollback(Long workId, Long targetReleaseId, RollbackReleaseRequest request);
}
