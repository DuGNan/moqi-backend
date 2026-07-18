package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
                   gmt_modified
            FROM chapter_briefs
            WHERE chapter_id = #{chapterId}
              AND deleted = 0
            ORDER BY gmt_modified DESC, id DESC
            LIMIT 1
            """)
    ChapterBriefEntity findLatestByChapterId(@Param("chapterId") Long chapterId);
}
