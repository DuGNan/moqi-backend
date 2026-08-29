package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.chapter.entity.ChapterTitleCandidateEntity;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 提供章节标题候选的持久化与采用锁定能力。
 */
public interface ChapterTitleCandidateMapper extends BaseMapper<ChapterTitleCandidateEntity> {

    /**
     * 在指定批次内锁定并返回未删除的标题候选。
     *
     * @param batchId 批次 ID
     * @param id 候选 ID
     * @return 标题候选，不存在时返回 null
     */
    @Select("SELECT * FROM chapter_title_candidates WHERE id = #{id} AND batch_id = #{batchId} "
            + "AND deleted = 0 FOR UPDATE")
    ChapterTitleCandidateEntity selectByIdForUpdate(@Param("batchId") Long batchId, @Param("id") Long id);
}
