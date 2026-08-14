package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefPreview;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 定义章节正文生成说明的固定版本编译与只读预览能力。
 */
public interface ChapterGenerationBriefService {

    /**
     * 编译指定、已校验发布规划的冻结生成说明。
     *
     * @param chapterId 章节 ID
     * @param plan 已发布场景规划
     * @return 结构化且可直接注入模型输入的生成说明
     */
    ChapterGenerationBrief compile(Long chapterId, ChapterPlanView plan);

    /**
     * 只读预览当前或指定发布规划对应的生成说明。
     *
     * @param chapterId 章节 ID
     * @param scenePlanNo 可选场景规划版本号
     * @return 生成说明预览
     */
    GenerationBriefPreview preview(Long chapterId, Integer scenePlanNo);
}
