package com.dugnan.moqi.planning;

import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 为后续正文生成按章节和明确版本读取已发布场景规划。
 */
public interface PublishedScenePlanQueryPort {
    ChapterPlanView loadCurrent(Long chapterId);

    ChapterPlanView loadPublished(Long chapterId, Integer planNo);
}
