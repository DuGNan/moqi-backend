package com.dugnan.moqi.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;

/**
 * @author dgn
 * @date 2026-07-13
 * @description 提供章节摘要及发布回退后重新生效的数据访问能力。
 */
public interface ChapterSummaryMapper extends BaseMapper<ChapterSummaryEntity> {
    /**
     * 锁定指定章节的已删除摘要，为回退后再次发布复用章节唯一行。
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @return 被回退软删除的摘要
     */
    @Select("SELECT * FROM chapter_summaries WHERE work_id=#{workId} AND chapter_id=#{chapterId} "
            + "AND deleted=1 LIMIT 1 FOR UPDATE")
    ChapterSummaryEntity deletedForUpdate(@Param("workId") Long workId, @Param("chapterId") Long chapterId);

    /**
     * 按当前版本重新激活摘要，显式绕开通用软删除更新过滤。
     * @param row 新的已确认摘要内容
     * @param expectedVersion 当前软删除行版本
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_summaries SET summary=#{row.summary},character_changes_json=#{row.characterChangesJson},
                new_settings_json=#{row.newSettingsJson},new_foreshadowing_json=#{row.newForeshadowingJson},
                open_questions_json=#{row.openQuestionsJson},summary_status=#{row.summaryStatus},
                content_revision=#{row.contentRevision},deleted=0,version=version+1,gmt_modified=NOW()
            WHERE id=#{row.id} AND work_id=#{row.workId} AND deleted=1 AND version=#{expectedVersion}
            """)
    int reactivate(@Param("row") ChapterSummaryEntity row, @Param("expectedVersion") Integer expectedVersion);
}
