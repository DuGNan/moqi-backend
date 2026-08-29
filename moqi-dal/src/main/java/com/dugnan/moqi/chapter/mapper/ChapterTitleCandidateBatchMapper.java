package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.chapter.entity.ChapterTitleCandidateBatchEntity;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 提供章节标题候选批次的查询与锁定能力。
 */
public interface ChapterTitleCandidateBatchMapper extends BaseMapper<ChapterTitleCandidateBatchEntity> {

    /**
     * 锁定并返回未删除的标题候选批次。
     *
     * @param id 批次 ID
     * @return 标题候选批次，不存在时返回 null
     */
    @Select("SELECT * FROM chapter_title_candidate_batches WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    ChapterTitleCandidateBatchEntity selectByIdForUpdate(@Param("id") Long id);
}
