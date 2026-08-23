package com.dugnan.moqi.planning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 提供章节规划版本的数据访问能力。
 */
public interface ChapterPlanVersionMapper extends BaseMapper<ChapterPlanVersionEntity> {

    /**
     * 锁定章节当前权威场景规划。
     *
     * @param chapterId 章节 ID
     * @return 当前规划
     */
    @Select("""
            SELECT * FROM chapter_plan_versions
            WHERE chapter_id = #{chapterId} AND current_marker = 1 AND deleted = 0
            FOR UPDATE
            """)
    ChapterPlanVersionEntity selectCurrentForUpdate(@Param("chapterId") Long chapterId);

    /**
     * 以版本条件将当前规划标记为被替代。
     *
     * @param id 规划 ID
     * @param version 基础版本
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_plan_versions
            SET plan_status = 'superseded', current_marker = NULL,
                validity_status = 'superseded', version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{id} AND version = #{version} AND current_marker = 1 AND deleted = 0
            """)
    int supersedeCurrentIfVersion(@Param("id") Long id, @Param("version") Integer version);

    /**
     * 查询章节规划最大序号。
     *
     * @param chapterId 章节 ID
     * @return 最大序号，没有记录时返回零
     */
    @Select("SELECT COALESCE(MAX(plan_no), 0) FROM chapter_plan_versions WHERE chapter_id = #{chapterId}")
    int selectMaxPlanNo(@Param("chapterId") Long chapterId);
}
