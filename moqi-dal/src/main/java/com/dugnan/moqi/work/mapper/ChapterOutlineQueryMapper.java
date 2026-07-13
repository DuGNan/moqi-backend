package com.dugnan.moqi.work.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.work.entity.ChapterOutlineEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供章节大纲及最新修订版本的查询能力。
 */
public interface ChapterOutlineQueryMapper extends BaseMapper<ChapterOutlineEntity> {
    @Select("""
            SELECT *
            FROM chapter_outlines
            WHERE chapter_id = #{chapterId}
              AND deleted = 0
            ORDER BY revision DESC, id DESC
            LIMIT 1
            """)
    ChapterOutlineEntity findLatest(@Param("chapterId") Long chapterId);
}
