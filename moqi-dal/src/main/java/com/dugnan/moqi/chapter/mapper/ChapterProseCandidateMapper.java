package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 提供稳定正文候选的 CAS 保存和历史状态同步能力。
 */
public interface ChapterProseCandidateMapper extends BaseMapper<ChapterProseCandidateEntity> {

    /**
     * 锁定章节内指定候选。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @return 锁定的候选，不存在时返回 null
     */
    @Select("""
            SELECT *
            FROM chapter_prose_candidates
            WHERE id = #{candidateId}
              AND chapter_id = #{chapterId}
              AND deleted = 0
            FOR UPDATE
            """)
    ChapterProseCandidateEntity selectByIdForUpdate(
            @Param("chapterId") Long chapterId,
            @Param("candidateId") Long candidateId);

    /**
     * 按内容版本保存同一稳定候选。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param content 正文内容
     * @param contentHash 内容哈希
     * @param wordCount 字数
     * @param qualityGenerationId 当前质量输入 generation ID
     * @param baseVersion 客户端内容基准版本
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_prose_candidates
            SET content = #{content},
                content_hash = #{contentHash},
                word_count = #{wordCount},
                quality_generation_id = #{qualityGenerationId},
                quality_request_status = 'pending',
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{candidateId}
              AND chapter_id = #{chapterId}
              AND version = #{baseVersion}
              AND deleted = 0
            """)
    int updateContentIfVersion(
            @Param("chapterId") Long chapterId,
            @Param("candidateId") Long candidateId,
            @Param("content") String content,
            @Param("contentHash") String contentHash,
            @Param("wordCount") Integer wordCount,
            @Param("qualityGenerationId") Long qualityGenerationId,
            @Param("baseVersion") Integer baseVersion);

    /**
     * 将旧 generation 采纳状态同步到候选历史目录。
     *
     * @param chapterId 章节 ID
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_prose_candidates candidate
            JOIN chapter_generations generation ON generation.id = candidate.source_generation_id
            SET candidate.candidate_status = CASE
                    WHEN generation.generation_status IN ('rejected', 'superseded') THEN 'history'
                    ELSE 'active'
                END,
                candidate.adoption_status = CASE
                    WHEN candidate.adoption_status IN ('adopted', 'release_pending')
                        THEN candidate.adoption_status
                    WHEN generation.generation_status = 'accepted' THEN 'adopted'
                    WHEN generation.generation_status = 'superseded' THEN 'replaced'
                    ELSE 'unadopted'
                END,
                candidate.gmt_modified = CURRENT_TIMESTAMP
            WHERE candidate.chapter_id = #{chapterId}
              AND candidate.deleted = 0
              AND generation.deleted = 0
            """)
    int synchronizeGenerationStatuses(@Param("chapterId") Long chapterId);

    /**
     * 更新指定质量输入的请求状态，不推进候选内容版本。
     *
     * @param generationId 质量输入 generation ID
     * @param requestStatus 请求状态
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_prose_candidates
            SET quality_request_status = #{requestStatus},
                gmt_modified = CURRENT_TIMESTAMP
            WHERE quality_generation_id = #{generationId}
              AND deleted = 0
            """)
    int updateQualityRequestStatus(
            @Param("generationId") Long generationId,
            @Param("requestStatus") String requestStatus);

    /**
     * 以候选版本和内容哈希为 CAS 条件推进采纳状态。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param candidateVersion 候选版本
     * @param contentHash 候选正文哈希
     * @param adoptionStatus 目标采纳状态
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_prose_candidates
            SET adoption_status = #{adoptionStatus}, gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{candidateId} AND chapter_id = #{chapterId} AND version = #{candidateVersion}
              AND content_hash = #{contentHash} AND adoption_status = 'unadopted' AND deleted = 0
            """)
    int markAdoptionStatus(
            @Param("chapterId") Long chapterId,
            @Param("candidateId") Long candidateId,
            @Param("candidateVersion") Integer candidateVersion,
            @Param("contentHash") String contentHash,
            @Param("adoptionStatus") String adoptionStatus);
}
