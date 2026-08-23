package com.dugnan.moqi.chapter.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 按稳定顺序锁定候选本次要结算的修改提案。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param proposalIds 修改提案 ID
     * @return 归属于该候选的修改提案
     */
    @Select("""
            <script>
            SELECT * FROM chapter_selection_assistance
            WHERE chapter_id = #{chapterId}
              AND (target_candidate_id = #{candidateId} OR created_candidate_id = #{candidateId})
              AND id IN
              <foreach collection="proposalIds" item="proposalId" open="(" separator="," close=")">
                #{proposalId}
              </foreach>
              AND deleted = 0
            ORDER BY id
            FOR UPDATE
            </script>
            """)
    List<ChapterSelectionAssistanceEntity> selectProposalsForUpdate(
            @Param("chapterId") Long chapterId,
            @Param("candidateId") Long candidateId,
            @Param("proposalIds") List<Long> proposalIds);

    /**
     * 原子记录修改提案对应的候选保存结果。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param proposalIds 修改提案 ID
     * @param targetVersion 保存前候选版本
     * @param targetHash 保存前候选哈希
     * @param resultVersion 保存后候选版本
     * @param resultHash 保存后候选哈希
     * @return 更新行数
     */
    @Update("""
            <script>
            UPDATE chapter_selection_assistance
            SET proposal_status = 'applied',
                applied_candidate_version = #{resultVersion},
                applied_candidate_hash = #{resultHash},
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE chapter_id = #{chapterId}
              AND (target_candidate_id = #{candidateId} OR created_candidate_id = #{candidateId})
              AND id IN
              <foreach collection="proposalIds" item="proposalId" open="(" separator="," close=")">
                #{proposalId}
              </foreach>
              AND request_status IN ('ready', 'review_required')
              AND proposal_status = 'ready'
              AND target_content_version = #{targetVersion}
              AND target_content_hash = #{targetHash}
              AND deleted = 0
            </script>
            """)
    int markProposalsApplied(
            @Param("chapterId") Long chapterId,
            @Param("candidateId") Long candidateId,
            @Param("proposalIds") List<Long> proposalIds,
            @Param("targetVersion") Integer targetVersion,
            @Param("targetHash") String targetHash,
            @Param("resultVersion") Integer resultVersion,
            @Param("resultHash") String resultHash);
}
