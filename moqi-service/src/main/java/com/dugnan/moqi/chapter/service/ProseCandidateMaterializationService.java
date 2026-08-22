package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 定义生成结果进入统一正文候选目录的幂等物化边界。
 */
public interface ProseCandidateMaterializationService {

    /**
     * 幂等物化一个完整正文生成结果。
     *
     * @param generation 已完成生成记录
     */
    void materialize(ChapterGenerationEntity generation);

    /**
     * 在生成事务提交后按稳定 ID 重新加载并物化正文候选。
     *
     * @param generationId 已提交生成记录 ID
     */
    void materializeByGenerationId(Long generationId);

    /**
     * 同步章节旧 generation 的采纳和替代状态。
     *
     * @param chapterId 章节 ID
     */
    void synchronizeChapterStatuses(Long chapterId);

    /**
     * 标记质量评价已成功创建。
     *
     * @param generationId 质量输入 generation ID
     */
    void markQualityRequested(Long generationId);

    /**
     * 标记质量评价当前不可用。
     *
     * @param generationId 质量输入 generation ID
     */
    void markQualityUnavailable(Long generationId);
}
