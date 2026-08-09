package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.ExperimentList;
import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.ExperimentView;
import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.RunExperimentRequest;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 定义不影响正式正文和候选的章节生成策略实验能力。
 */
public interface ChapterGenerationExperimentService {

    /**
     * 执行或幂等读取一份隔离实验样本。
     *
     * @param chapterId 章节 ID
     * @param request 实验参数
     * @return 实验结果
     */
    ExperimentView run(Long chapterId, RunExperimentRequest request);

    /**
     * 查询指定章节和分组的实验结果。
     *
     * @param chapterId 章节 ID
     * @param experimentGroupKey 实验分组键
     * @return 有序实验结果
     */
    ExperimentList list(Long chapterId, String experimentGroupKey);
}
