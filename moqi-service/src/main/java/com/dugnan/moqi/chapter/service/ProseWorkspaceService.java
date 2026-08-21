package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateDetail;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseWorkspaceView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.SaveProseCandidateRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.SaveWorkspaceSelectionRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.WorkspaceSelectionView;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 定义统一章节正文工作区的只读目录、选择恢复和候选显式保存能力。
 */
public interface ProseWorkspaceService {

    /**
     * 读取章节正式正文、候选目录、选择和运行任务。
     *
     * @param chapterId 章节 ID
     * @return 只读工作区
     */
    ProseWorkspaceView getWorkspace(Long chapterId);

    /**
     * 按版本保存当前稳定对象选择。
     *
     * @param chapterId 章节 ID
     * @param request 选择请求
     * @return 已保存选择
     */
    WorkspaceSelectionView saveSelection(Long chapterId, SaveWorkspaceSelectionRequest request);

    /**
     * 读取稳定候选正文详情。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @return 候选详情
     */
    ProseCandidateDetail getCandidate(Long chapterId, Long candidateId);

    /**
     * 按内容版本显式保存同一候选。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param request 保存请求
     * @return 保存后的候选详情
     */
    ProseCandidateDetail saveCandidate(Long chapterId, Long candidateId, SaveProseCandidateRequest request);
}
