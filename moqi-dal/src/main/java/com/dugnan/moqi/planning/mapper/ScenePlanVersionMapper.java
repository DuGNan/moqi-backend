package com.dugnan.moqi.planning.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 提供场景规划叶子节点的数据访问能力。
 */
public interface ScenePlanVersionMapper extends BaseMapper<ScenePlanVersionEntity> {

    /**
     * 查询候选下包含已删除记录在内的全部场景。
     *
     * @param planId 章节规划候选 ID
     * @return 全部场景记录
     */
    @Select("""
            SELECT * FROM scene_plan_versions
            WHERE chapter_plan_version_id = #{planId}
            ORDER BY id ASC
            """)
    List<ScenePlanVersionEntity> findAllByPlanId(@Param("planId") Long planId);

    /**
     * 按乐观版本原位更新或恢复一个场景。
     *
     * @param id 场景记录 ID
     * @param sequenceNo 场景顺序
     * @param contentSchemaVersion 内容 schema 版本
     * @param contentJson 场景内容
     * @param version 乐观锁版本
     * @return 更新行数
     */
    @Update("""
            UPDATE scene_plan_versions
            SET sequence_no = #{sequenceNo}, content_schema_version = #{contentSchemaVersion},
                content_json = #{contentJson}, deleted = 0, version = version + 1
            WHERE id = #{id} AND version = #{version}
            """)
    int updateContent(
            @Param("id") Long id,
            @Param("sequenceNo") Integer sequenceNo,
            @Param("contentSchemaVersion") Integer contentSchemaVersion,
            @Param("contentJson") String contentJson,
            @Param("version") Integer version);

    /**
     * 按乐观版本软删除候选中已移除的场景。
     *
     * @param id 场景记录 ID
     * @param version 乐观锁版本
     * @return 更新行数
     */
    @Update("""
            UPDATE scene_plan_versions
            SET deleted = 1, version = version + 1
            WHERE id = #{id} AND version = #{version} AND deleted = 0
            """)
    int markDeleted(@Param("id") Long id, @Param("version") Integer version);
}
