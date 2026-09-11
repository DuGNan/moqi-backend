package com.dugnan.moqi.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.release.entity.ChapterProseRevisionEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 提供章节正文 revision 的持久化能力。
 */
public interface ChapterProseRevisionMapper extends BaseMapper<ChapterProseRevisionEntity> {

    /** 锁定未删除的正文 revision 行。 */
    @Select("SELECT * FROM chapter_prose_revisions WHERE id = #{revisionId} AND deleted = 0 FOR UPDATE")
    ChapterProseRevisionEntity selectByIdForUpdate(@Param("revisionId") Long revisionId);
}
