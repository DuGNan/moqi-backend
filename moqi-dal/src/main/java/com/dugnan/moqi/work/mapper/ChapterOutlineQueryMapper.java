package com.dugnan.moqi.work.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.work.entity.ChapterOutlineEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供章节大纲及最新修订版本的查询能力。
 */
public interface ChapterOutlineQueryMapper extends BaseMapper<ChapterOutlineEntity> {
    /**
     * 查询章节最新的未删除大纲版本。
     *
     * @param chapterId 章节 ID
     * @return 最新大纲，不存在时返回 null
     */
    @Select("""
            SELECT *
            FROM chapter_outlines
            WHERE chapter_id = #{chapterId}
              AND deleted = 0
            ORDER BY revision DESC, id DESC
            LIMIT 1
            """)
    ChapterOutlineEntity findLatest(@Param("chapterId") Long chapterId);

    /**
     * 按业务修订和实体版本条件更新正式大纲，避免候选确认与手工保存相互覆盖。
     *
     * @param id 大纲 ID
     * @param chapterId 章节 ID
     * @param confirmedBriefId 已确认 Brief ID
     * @param outlineStatus 大纲状态
     * @param outlineContent 大纲内容 JSON
     * @param baseRevision 客户端或候选读取的基础修订
     * @param baseVersion 实体基础版本
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_outlines
            SET confirmed_brief_id = #{confirmedBriefId},
                outline_status = #{outlineStatus},
                outline_content = #{outlineContent},
                revision = revision + 1,
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND chapter_id = #{chapterId}
              AND revision = #{baseRevision}
              AND version = #{baseVersion}
              AND deleted = 0
            """)
    int updateByRevisionAndVersion(
            @Param("id") Long id,
            @Param("chapterId") Long chapterId,
            @Param("confirmedBriefId") Long confirmedBriefId,
            @Param("outlineStatus") String outlineStatus,
            @Param("outlineContent") String outlineContent,
            @Param("baseRevision") Integer baseRevision,
            @Param("baseVersion") Integer baseVersion);
}
