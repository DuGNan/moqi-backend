package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供章节简报的数据访问能力。
 */
public interface ChapterBriefMapper extends BaseMapper<ChapterBriefEntity> {

    /**
     * 查询章节最近的 brief，数据库侧排序并限制一条。
     *
     * @param chapterId 章节 ID
     * @return 最近 brief，不存在时返回 null
     */
    @Select("""
            SELECT id,
                   work_id,
                   chapter_id,
                   brief_status,
                   brief_content,
                   deleted,
                   version,
                   gmt_create,
                   gmt_modified
            FROM chapter_briefs
            WHERE chapter_id = #{chapterId}
              AND deleted = 0
            ORDER BY gmt_modified DESC, id DESC
            LIMIT 1
            """)
    ChapterBriefEntity findLatestByChapterId(@Param("chapterId") Long chapterId);

    /**
     * 按状态查询章节最近的 Brief。
     *
     * @param chapterId 章节 ID
     * @param briefStatus Brief 状态
     * @return 最近 Brief，不存在时返回 null
     */
    @Select("""
            SELECT id,
                   work_id,
                   chapter_id,
                   brief_status,
                   brief_content,
                   deleted,
                   version,
                   gmt_create,
                   gmt_modified
            FROM chapter_briefs
            WHERE chapter_id = #{chapterId}
              AND brief_status = #{briefStatus}
              AND deleted = 0
            ORDER BY gmt_modified DESC, id DESC
            LIMIT 1
            """)
    ChapterBriefEntity findLatestByChapterIdAndStatus(
            @Param("chapterId") Long chapterId,
            @Param("briefStatus") String briefStatus);

    /**
     * 在章节归属范围内查询 Brief。
     *
     * @param id Brief ID
     * @param chapterId 章节 ID
     * @return Brief，不存在时返回 null
     */
    @Select("""
            SELECT id,
                   work_id,
                   chapter_id,
                   brief_status,
                   brief_content,
                   deleted,
                   version,
                   gmt_create,
                   gmt_modified
            FROM chapter_briefs
            WHERE id = #{id}
              AND chapter_id = #{chapterId}
              AND deleted = 0
            LIMIT 1
            """)
    ChapterBriefEntity findByIdAndChapterId(
            @Param("id") Long id,
            @Param("chapterId") Long chapterId);

    /**
     * 使用状态与版本条件将草稿确认为正式 Brief。
     *
     * @param id Brief ID
     * @param chapterId 章节 ID
     * @param expectedVersion 预期版本
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_briefs
            SET brief_status = 'confirmed',
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND chapter_id = #{chapterId}
              AND brief_status = 'draft'
              AND version = #{expectedVersion}
              AND deleted = 0
            """)
    int confirmDraft(
            @Param("id") Long id,
            @Param("chapterId") Long chapterId,
            @Param("expectedVersion") Integer expectedVersion);
}
