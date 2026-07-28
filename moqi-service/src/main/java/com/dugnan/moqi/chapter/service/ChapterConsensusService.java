package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefState;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefView;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConfirmBriefRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.CreateBriefDraftRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.DecisionSources;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 定义章节结构化共识草稿、确认和来源追溯能力。
 */
public interface ChapterConsensusService {

    /**
     * 查询章节最新草稿和最新确认版本。
     *
     * @param chapterId 章节 ID
     * @return Brief 状态
     */
    BriefState getState(Long chapterId);

    /**
     * 追加一个结构化 Brief 草稿。
     *
     * @param chapterId 章节 ID
     * @param request 草稿请求
     * @return 新草稿
     */
    BriefView createDraft(Long chapterId, CreateBriefDraftRequest request);

    /**
     * 使用乐观条件确认 Brief。
     *
     * @param chapterId 章节 ID
     * @param briefId Brief ID
     * @param request 确认请求
     * @return 已确认 Brief
     */
    BriefView confirm(Long chapterId, Long briefId, ConfirmBriefRequest request);

    /**
     * 查询某个待决引用的讨论消息。
     *
     * @param chapterId 章节 ID
     * @param briefId Brief ID
     * @param decisionKey 待决键
     * @return 来源消息
     */
    DecisionSources getDecisionSources(Long chapterId, Long briefId, String decisionKey);
}
