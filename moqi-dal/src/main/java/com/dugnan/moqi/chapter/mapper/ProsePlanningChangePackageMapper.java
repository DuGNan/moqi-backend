package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.chapter.entity.ProsePlanningChangePackageEntity;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 提供正文规划变更包的锁定、幂等读取和应用状态更新能力。
 */
public interface ProsePlanningChangePackageMapper extends BaseMapper<ProsePlanningChangePackageEntity> {

    /**
     * 锁定章节中的规划变更包。
     *
     * @param chapterId 章节 ID
     * @param packageId 规划变更包 ID
     * @return 规划变更包
     */
    @Select("""
            SELECT * FROM prose_planning_change_packages
            WHERE id = #{packageId} AND chapter_id = #{chapterId} AND deleted = 0
            FOR UPDATE
            """)
    ProsePlanningChangePackageEntity selectByIdForUpdate(
            @Param("chapterId") Long chapterId,
            @Param("packageId") Long packageId);

    /**
     * 按包版本标记已原子应用。
     *
     * @param packageId 规划变更包 ID
     * @param candidateVersion 应用后的候选版本
     * @param candidateHash 应用后的候选哈希
     * @param resultScenePlanId 新权威场景规划 ID
     * @param baseVersion 变更包基础版本
     * @return 更新行数
     */
    @Update("""
            UPDATE prose_planning_change_packages
            SET package_status = 'applied',
                applied_candidate_version = #{candidateVersion},
                applied_candidate_hash = #{candidateHash},
                result_scene_plan_id = #{resultScenePlanId},
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{packageId}
              AND package_status = 'candidate'
              AND version = #{baseVersion}
              AND deleted = 0
            """)
    int markApplied(
            @Param("packageId") Long packageId,
            @Param("candidateVersion") Integer candidateVersion,
            @Param("candidateHash") String candidateHash,
            @Param("resultScenePlanId") Long resultScenePlanId,
            @Param("baseVersion") Integer baseVersion);
}
