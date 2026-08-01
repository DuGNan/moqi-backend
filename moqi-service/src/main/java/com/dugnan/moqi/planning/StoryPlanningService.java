package com.dugnan.moqi.planning;

import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.CreateNarrativePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.CreateScenePlanCandidateRequest;
import com.dugnan.moqi.planning.PlanningModels.NarrativePlanView;
import com.dugnan.moqi.planning.PlanningModels.PublishNarrativePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.PublishScenePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.UpdateNarrativePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.UpdateScenePlanCandidateRequest;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 定义作品叙事规划与章节场景规划的应用服务边界。
 */
public interface StoryPlanningService extends PublishedScenePlanQueryPort {
    NarrativePlanView createNarrativePlan(Long workId, CreateNarrativePlanRequest request);
    NarrativePlanView getNarrativePlan(Long workId, Long planId);
    NarrativePlanView getCurrentNarrativePlan(Long workId);
    NarrativePlanView updateNarrativePlan(Long workId, Long planId, UpdateNarrativePlanRequest request);
    NarrativePlanView publishNarrativePlan(Long workId, Long planId, PublishNarrativePlanRequest request);
    ChapterPlanView createCandidate(Long chapterId, CreateScenePlanCandidateRequest request);
    ChapterPlanView getCandidate(Long chapterId, Long planId);
    ChapterPlanView getLatestCandidate(Long chapterId);
    ChapterPlanView updateCandidate(Long chapterId, Long planId, UpdateScenePlanCandidateRequest request);
    ChapterPlanView abandonCandidate(Long chapterId, Long planId);
    ChapterPlanView publishCandidate(Long chapterId, Long planId, PublishScenePlanRequest request);
}
