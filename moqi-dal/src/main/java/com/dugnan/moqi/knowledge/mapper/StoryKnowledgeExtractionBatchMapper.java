package com.dugnan.moqi.knowledge.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.knowledge.entity.StoryKnowledgeExtractionBatchEntity;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 提供故事知识提取批次的数据访问能力。
 */
public interface StoryKnowledgeExtractionBatchMapper
        extends BaseMapper<StoryKnowledgeExtractionBatchEntity> {

    /**
     * 读取此次发布中发生正文变化的章节在精确基线下的就绪批次。
     *
     * @param workId 作品 ID
     * @param releaseId 待激活发布版本
     * @param baselineReleaseId 当前发布基线
     * @return 就绪知识批次
     */
    @Select("""
            SELECT b.* FROM story_knowledge_extraction_batches b
            JOIN story_release_chapters n ON n.prose_revision_id = b.source_prose_revision_id
                AND n.work_id = b.work_id AND n.chapter_id = b.chapter_id AND n.deleted = 0
            WHERE n.release_id = #{releaseId} AND b.work_id = #{workId}
                AND b.source_story_release_id <=> #{baselineReleaseId}
                AND b.batch_status = 'ready' AND b.deleted = 0
                AND b.id = (
                    SELECT MAX(latest.id) FROM story_knowledge_extraction_batches latest
                    WHERE latest.work_id = b.work_id AND latest.chapter_id = b.chapter_id
                        AND latest.source_prose_revision_id = b.source_prose_revision_id
                        AND latest.source_story_release_id <=> b.source_story_release_id
                        AND latest.deleted = 0)
                AND NOT EXISTS (
                    SELECT 1 FROM story_release_chapters p
                    WHERE p.release_id = #{baselineReleaseId} AND p.chapter_id = n.chapter_id
                        AND p.prose_revision_id = n.prose_revision_id AND p.deleted = 0)
            ORDER BY b.chapter_id, b.id
            """)
    List<StoryKnowledgeExtractionBatchEntity> forRelease(@Param("workId") Long workId,
            @Param("releaseId") Long releaseId, @Param("baselineReleaseId") Long baselineReleaseId);
}
