package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.chapter.entity.ChapterOutlineCandidateEntity;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 提供章节大纲调整候选的查询和确认锁定能力。
 */
public interface ChapterOutlineCandidateMapper extends BaseMapper<ChapterOutlineCandidateEntity> {

    /**
     * 查询章节最近创建的候选。
     *
     * @param chapterId 章节 ID
     * @return 最新候选，不存在时返回 null
     */
    @Select("""
            SELECT *
            FROM chapter_outline_candidates
            WHERE chapter_id = #{chapterId}
              AND deleted = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    ChapterOutlineCandidateEntity findLatest(@Param("chapterId") Long chapterId);

    /**
     * 查询任务关联的候选。
     *
     * @param taskId AI 任务 ID
     * @return 候选，不存在时返回 null
     */
    @Select("""
            SELECT *
            FROM chapter_outline_candidates
            WHERE ai_task_id = #{taskId}
              AND deleted = 0
            LIMIT 1
            """)
    ChapterOutlineCandidateEntity findByTaskId(@Param("taskId") Long taskId);

    /**
     * 按章节和幂等键查询候选。
     *
     * @param chapterId 章节 ID
     * @param idempotencyKey 幂等键
     * @return 已存在候选，不存在时返回 null
     */
    @Select("""
            SELECT *
            FROM chapter_outline_candidates
            WHERE chapter_id = #{chapterId}
              AND idempotency_key = #{idempotencyKey}
              AND deleted = 0
            LIMIT 1
            """)
    ChapterOutlineCandidateEntity findByIdempotencyKey(
            @Param("chapterId") Long chapterId,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 查询同章尚未处理完的首版候选。
     *
     * @param chapterId 章节 ID
     * @return 活跃首版候选，不存在时返回 null
     */
    @Select("""
            SELECT *
            FROM chapter_outline_candidates
            WHERE chapter_id = #{chapterId}
              AND candidate_type = 'initial'
              AND candidate_status IN ('queued', 'running', 'ready')
              AND deleted = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    ChapterOutlineCandidateEntity findActiveInitial(@Param("chapterId") Long chapterId);

    /**
     * 锁定候选，供确认事务重新验证状态和基础版本。
     *
     * @param candidateId 候选 ID
     * @param chapterId 章节 ID
     * @return 已锁定候选，不存在时返回 null
     */
    @Select("""
            SELECT *
            FROM chapter_outline_candidates
            WHERE id = #{candidateId}
              AND chapter_id = #{chapterId}
              AND deleted = 0
            FOR UPDATE
            """)
    ChapterOutlineCandidateEntity findByIdForUpdate(
            @Param("candidateId") Long candidateId,
            @Param("chapterId") Long chapterId);
}
