package com.dugnan.moqi.chapter.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 提供正文一致性评价报告的数据访问能力。
 */
public interface ChapterGenerationEvaluationReportMapper extends BaseMapper<ChapterGenerationEvaluationReportEntity> {

    /**
     * 按章节和报告 ID 锁定精确质量报告。
     *
     * @param chapterId 章节 ID
     * @param reportId 报告 ID
     * @return 已锁定报告
     */
    @Select("""
            SELECT * FROM chapter_generation_evaluation_reports
            WHERE id = #{reportId} AND chapter_id = #{chapterId} AND deleted = 0
            FOR UPDATE
            """)
    ChapterGenerationEvaluationReportEntity selectByIdForUpdate(
            @Param("chapterId") Long chapterId,
            @Param("reportId") Long reportId);

    /**
     * 按固定顺序锁定一次生成的全部整章质量报告。
     *
     * @param generationId 质量输入 generation ID
     * @return 按新到旧锁定的整章报告
     */
    @Select("""
            SELECT * FROM chapter_generation_evaluation_reports
            WHERE generation_id = #{generationId} AND generation_scene_id IS NULL AND deleted = 0
            ORDER BY id DESC
            FOR UPDATE
            """)
    List<ChapterGenerationEvaluationReportEntity> selectWholeReportsForUpdate(
            @Param("generationId") Long generationId);
}
