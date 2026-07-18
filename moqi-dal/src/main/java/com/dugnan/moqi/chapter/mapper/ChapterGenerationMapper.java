package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供章节生成记录的数据访问能力。
 */
public interface ChapterGenerationMapper extends BaseMapper<ChapterGenerationEntity> {

    /**
     * 按当前状态条件流转生成记录。
     *
     * @param generationId 生成记录 ID
     * @param currentStatus 当前状态
     * @param nextStatus 目标状态
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_generations
            SET generation_status = #{nextStatus},
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{generationId}
              AND generation_status = #{currentStatus}
              AND deleted = 0
            """)
    int updateStatusIfCurrent(
            @Param("generationId") Long generationId,
            @Param("currentStatus") String currentStatus,
            @Param("nextStatus") String nextStatus);
}
