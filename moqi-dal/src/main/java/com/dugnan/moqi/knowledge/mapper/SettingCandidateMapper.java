package com.dugnan.moqi.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.knowledge.entity.SettingCandidateEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供待确认设定候选的数据访问能力。
 */
public interface SettingCandidateMapper extends BaseMapper<SettingCandidateEntity> {

    /**
     * 按主键锁定未删除候选，串行化候选确认事务。
     *
     * @param id 候选 ID
     * @return 已锁定候选，不存在时返回 null
     */
    @Select("""
            SELECT id, work_id, chapter_id, source_type, source_id, source_content_revision,
                   source_start_offset, source_end_offset, setting_type, name, content,
                   candidate_status, confirmed_setting_id, deleted, version, gmt_create, gmt_modified
            FROM setting_candidates
            WHERE id = #{id}
              AND deleted = 0
            FOR UPDATE
            """)
    SettingCandidateEntity selectByIdForUpdate(@Param("id") Long id);
}
