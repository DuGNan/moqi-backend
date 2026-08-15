package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供章节生成记录的数据访问能力。
 */
public interface ChapterGenerationMapper extends BaseMapper<ChapterGenerationEntity> {

    /**
     * 查询章节最近的待处理预览，仅读取摘要字段。
     *
     * @param chapterId 章节 ID
     * @return 最近预览，不存在时返回 null
     */
    @Select("""
            SELECT id,
                   chapter_id,
                   generation_status,
                   generation_mode,
                   word_count,
                   gmt_create
            FROM chapter_generations
            WHERE chapter_id = #{chapterId}
              AND generation_status = 'preview'
              AND deleted = 0
            ORDER BY gmt_create DESC, id DESC
            LIMIT 1
            """)
    ChapterGenerationEntity findLatestPreview(@Param("chapterId") Long chapterId);

    /**
     * 查询章节最新仍需前端恢复的生成记录，仅读取恢复摘要字段。
     *
     * @param chapterId 章节 ID
     * @return 最新活动生成，不存在时返回 null
     */
    @Select("""
            SELECT id,
                   chapter_id,
                   generation_status,
                   content_assembly_mode,
                   ai_task_id,
                   agent_run_id,
                   gmt_modified
            FROM chapter_generations
            WHERE chapter_id = #{chapterId}
              AND generation_status IN ('queued', 'running', 'failed', 'preview')
              AND deleted = 0
            ORDER BY gmt_modified DESC, id DESC
            LIMIT 1
            """)
    ChapterGenerationEntity findLatestActive(@Param("chapterId") Long chapterId);

    /**
     * 按当前状态条件流转生成记录。
     *
     * @param generationId 生成记录 ID
     * @param currentStatus 当前状态
     * @param nextStatus 目标状态
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_generations
            SET generation_status = #{nextStatus},
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{generationId}
              AND generation_status = #{currentStatus}
              AND deleted = 0
            """)
    int updateStatusIfCurrent(
            @Param("generationId") Long generationId,
            @Param("currentStatus") String currentStatus,
            @Param("nextStatus") String nextStatus);

    /**
     * 将已采纳批次之前遗留的候选预览标记为已替代，避免其继续抢占正文入口。
     *
     * @param chapterId 章节 ID
     * @param acceptedGenerationId 已采纳批次 ID
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_generations preview
            JOIN chapter_generations accepted ON accepted.id = #{acceptedGenerationId}
            SET preview.generation_status = 'superseded',
                preview.version = preview.version + 1,
                preview.gmt_modified = CURRENT_TIMESTAMP
            WHERE preview.chapter_id = #{chapterId}
              AND preview.generation_status = 'preview'
              AND preview.deleted = 0
              AND (preview.gmt_create < accepted.gmt_create
                   OR (preview.gmt_create = accepted.gmt_create AND preview.id < accepted.id))
            """)
    int supersedeOlderPreviews(
            @Param("chapterId") Long chapterId,
            @Param("acceptedGenerationId") Long acceptedGenerationId);
}
