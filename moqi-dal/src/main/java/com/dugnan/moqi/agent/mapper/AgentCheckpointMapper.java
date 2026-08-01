package com.dugnan.moqi.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.agent.entity.AgentCheckpointEntity;

/**
 * Agent checkpoint 数据访问。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 提供 Agent checkpoint 的持久化和最新记录查询。
 */
public interface AgentCheckpointMapper extends BaseMapper<AgentCheckpointEntity> {

    /**
     * 查询 Run 的最新 checkpoint。
     *
     * @param runId Run ID
     * @return 最新 checkpoint，不存在时返回 null
     */
    @Select("SELECT * FROM agent_checkpoints WHERE run_id = #{runId} AND deleted = 0 ORDER BY sequence_id DESC LIMIT 1")
    AgentCheckpointEntity findLatestByRunId(Long runId);
}
