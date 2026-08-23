package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 提供章节选区讨论与局部改写候选的数据访问能力。
 */
public interface ChapterSelectionAssistanceMapper extends BaseMapper<ChapterSelectionAssistanceEntity> {

    /**
     * 锁定指定正文协助，供规划包创建串行检查唯一绑定。
     *
     * @param assistanceId 正文协助 ID
     * @return 锁定的协助，不存在时返回 null
     */
    @Select("SELECT * FROM chapter_selection_assistance WHERE id = #{assistanceId} AND deleted = 0 FOR UPDATE")
    ChapterSelectionAssistanceEntity selectByIdForUpdate(@Param("assistanceId") Long assistanceId);
}
