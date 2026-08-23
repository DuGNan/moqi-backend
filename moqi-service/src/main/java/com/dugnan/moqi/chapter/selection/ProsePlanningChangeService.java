package com.dugnan.moqi.chapter.selection;

import java.util.List;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ModelPlanningProposal;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningContext;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningChangePackageView;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 定义正文候选保存与权威场景规划变更的原子协作边界。
 */
public interface ProsePlanningChangeService {

    /**
     * 冻结正文协助模型可见的当前权威规划。
     *
     * @param chapterId 章节 ID
     * @return 当前规划上下文；章节尚无有效规划时返回 null
     */
    PlanningContext freezeContext(Long chapterId);

    /**
     * 将同一次正文协助模型输出持久化为唯一的待确认规划变更包。
     *
     * @param assistanceId 正文协助 ID
     * @param proposal 已通过结构解析的模型规划提案
     * @return 持久化规划变更包
     */
    PlanningChangePackageView createCandidate(Long assistanceId, ModelPlanningProposal proposal);

    /**
     * 按正文修改提案读取规划变更包。
     *
     * @param assistanceId 正文协助 ID
     * @return 已绑定的规划变更包，不存在时返回 null
     */
    PlanningChangePackageView getByAssistance(Long assistanceId);

    /**
     * 在候选保存事务内应用规划包。
     *
     * @param chapterId 章节 ID
     * @param candidate 已锁定的正文候选
     * @param packageId 规划变更包 ID
     * @param savedCandidateVersion 候选保存后的版本
     * @param savedCandidateHash 候选保存后的哈希
     * @param appliedProposalIds 作者明确应用的修改提案 ID
     * @return 新权威场景规划 ID
     */
    Long apply(
            Long chapterId,
            ChapterProseCandidateEntity candidate,
            Long packageId,
            Integer savedCandidateVersion,
            String savedCandidateHash,
            List<Long> appliedProposalIds);

    /**
     * 验证重复候选保存对应的规划包已经以相同结果应用。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param packageId 规划变更包 ID
     * @param savedCandidateVersion 已保存候选版本
     * @param savedCandidateHash 已保存候选哈希
     * @param appliedProposalIds 作者明确应用的修改提案 ID
     */
    void requireApplied(
            Long chapterId,
            Long candidateId,
            Long packageId,
            Integer savedCandidateVersion,
            String savedCandidateHash,
            List<Long> appliedProposalIds);
}
