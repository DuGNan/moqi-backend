package com.dugnan.moqi.planning;

import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CloneScenePlanCandidateRequest;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CreateOutlineRevisionCandidateRequest;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.OutlineRevisionCandidateCreated;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.ScenePlanRevisionDraft;

/**
 * @author dgn
 * @date 2026-08-12
 * @description 定义场景修订草稿克隆和章纲修订候选来源桥接能力。
 */
public interface SceneOutlineRevisionBridgeService {

    /**
     * 从同章当前已发布规划克隆修订草稿。
     *
     * @param chapterId 章节 ID
     * @param request 来源、章纲版本和幂等键
     * @return 可编辑但未发布的修订草稿引用
     */
    ScenePlanRevisionDraft cloneFromCurrent(Long chapterId, CloneScenePlanCandidateRequest request);

    /**
     * 根据已检查的场景修订差异创建章纲调整候选。
     *
     * @param chapterId 章节 ID
     * @param planId 场景修订草稿 ID
     * @param request 场景版本、报告和章纲候选来源
     * @return 章纲候选任务与确定性场景差异
     */
    OutlineRevisionCandidateCreated createOutlineCandidate(
            Long chapterId,
            Long planId,
            CreateOutlineRevisionCandidateRequest request);
}
