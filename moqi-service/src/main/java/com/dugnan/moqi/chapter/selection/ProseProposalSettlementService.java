package com.dugnan.moqi.chapter.selection;

import java.util.List;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 在候选显式保存事务中校验并结算作者已应用的正文修改提案。
 */
public interface ProseProposalSettlementService {

    /**
     * 锁定并校验待结算提案。
     *
     * @param chapterId 章节 ID
     * @param candidate 已锁定候选
     * @param proposalIds 作者明确应用的提案 ID
     */
    void validateForSave(Long chapterId, ChapterProseCandidateEntity candidate, List<Long> proposalIds);

    /**
     * 标记提案已随候选内容保存。
     *
     * @param chapterId 章节 ID
     * @param candidate 保存前候选
     * @param proposalIds 作者明确应用的提案 ID
     * @param resultVersion 保存后候选版本
     * @param resultHash 保存后候选哈希
     */
    void markApplied(
            Long chapterId,
            ChapterProseCandidateEntity candidate,
            List<Long> proposalIds,
            Integer resultVersion,
            String resultHash);

    /**
     * 验证幂等重放已经结算为相同候选结果。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param proposalIds 作者明确应用的提案 ID
     * @param resultVersion 已保存候选版本
     * @param resultHash 已保存候选哈希
     */
    void requireApplied(
            Long chapterId,
            Long candidateId,
            List<Long> proposalIds,
            Integer resultVersion,
            String resultHash);
}
