package com.dugnan.moqi.chapter.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.chapter.entity.ProseCandidateAdoptionEntity;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 提供候选采纳幂等锁定、影响分析恢复和结果推进能力。
 */
public interface ProseCandidateAdoptionMapper extends BaseMapper<ProseCandidateAdoptionEntity> {

    /**
     * 按章节和幂等键锁定已有采纳记录。
     *
     * @param chapterId 章节 ID
     * @param idempotencyKey 采纳幂等键
     * @return 已锁定的重放记录
     */
    @Select("""
            SELECT * FROM chapter_prose_candidate_adoptions
            WHERE chapter_id = #{chapterId} AND idempotency_key = #{idempotencyKey} AND deleted = 0
            FOR UPDATE
            """)
    ProseCandidateAdoptionEntity selectReplayForUpdate(
            @Param("chapterId") Long chapterId,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 查询服务恢复时仍待启动影响分析的采纳记录。
     *
     * @param limit 单批恢复上限
     * @return 待启动影响分析的采纳记录
     */
    @Select("""
            SELECT * FROM chapter_prose_candidate_adoptions
            WHERE adoption_status = 'impact_pending' AND deleted = 0
            ORDER BY id
            LIMIT #{limit}
            """)
    List<ProseCandidateAdoptionEntity> selectPendingImpact(@Param("limit") int limit);

    /**
     * 仅首次把影响报告绑定到待启动采纳记录。
     *
     * @param adoptionId 采纳记录 ID
     * @param reportId 影响报告 ID
     * @return 更新行数，已绑定同一报告时为零
     */
    @Update("""
            UPDATE chapter_prose_candidate_adoptions
            SET impact_report_id = #{reportId}, adoption_status = 'impact_running',
                error_code = NULL, version = version + 1, gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{adoptionId} AND adoption_status = 'impact_pending'
              AND impact_report_id IS NULL AND deleted = 0
            """)
    int bindImpactReport(@Param("adoptionId") Long adoptionId, @Param("reportId") Long reportId);

    /**
     * 保持待启动状态并记录可安全公开的恢复错误码。
     *
     * @param adoptionId 采纳记录 ID
     * @param errorCode 安全恢复错误码
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_prose_candidate_adoptions
            SET error_code = #{errorCode}, version = version + 1, gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{adoptionId} AND adoption_status = 'impact_pending'
              AND impact_report_id IS NULL AND deleted = 0
            """)
    int markImpactStartFailed(@Param("adoptionId") Long adoptionId, @Param("errorCode") String errorCode);
}
