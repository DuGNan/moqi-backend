package com.dugnan.moqi.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.agent.entity.AgentInterruptionEntity;

/**
 * Agent 人工中断数据访问。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 提供 Agent 人工中断的持久化和最新记录查询。
 */
public interface AgentInterruptionMapper extends BaseMapper<AgentInterruptionEntity> {

    /**
     * 查询 Run 的最新人工中断。
     *
     * @param runId Run ID
     * @return 最新人工中断，不存在时返回 null
     */
    @Select("SELECT * FROM agent_interruptions WHERE run_id = #{runId} AND deleted = 0 ORDER BY id DESC LIMIT 1")
    AgentInterruptionEntity findLatestByRunId(Long runId);
}
