package com.dugnan.moqi.chapter.selection;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.CreatePlanningChangePackageRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningChangePackageView;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 定义正文候选保存与权威场景规划变更的原子协作边界。
 */
public interface ProsePlanningChangeService {

    /**
     * 创建或复用待确认规划变更包。
     *
     * @param assistanceId 正文协助 ID
     * @param request 规划变更请求
     * @return 持久化规划变更包
     */
    PlanningChangePackageView create(Long assistanceId, CreatePlanningChangePackageRequest request);

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
     * @return 新权威场景规划 ID
     */
    Long apply(
            Long chapterId,
            ChapterProseCandidateEntity candidate,
            Long packageId,
            Integer savedCandidateVersion,
            String savedCandidateHash);

    /**
     * 验证重复候选保存对应的规划包已经以相同结果应用。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param packageId 规划变更包 ID
     * @param savedCandidateVersion 已保存候选版本
     * @param savedCandidateHash 已保存候选哈希
     */
    void requireApplied(
            Long chapterId,
            Long candidateId,
            Long packageId,
            Integer savedCandidateVersion,
            String savedCandidateHash);
}
