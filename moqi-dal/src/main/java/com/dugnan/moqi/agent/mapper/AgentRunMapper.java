package com.dugnan.moqi.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.agent.entity.AgentRunEntity;

/**
 * Agent Run 数据访问。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 提供 Agent Run 持久化和关联 AI Task 查询。
 */
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    /**
     * 根据关联 AI Task 查询 Run。
     *
     * @param taskId AI Task ID
     * @return Run，不存在时返回 null
     */
    @Select("SELECT * FROM agent_runs WHERE ai_task_id = #{taskId} AND deleted = 0")
    AgentRunEntity findByAiTaskId(Long taskId);
}
