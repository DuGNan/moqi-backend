package com.dugnan.moqi.chapter.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.dugnan.moqi.chapter.entity.LlmModelCallEntity;
import com.dugnan.moqi.llm.dto.LlmCallAggregateRow;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 提供模型调用审计记录的数据访问能力。
 */
public interface LlmModelCallMapper extends BaseMapper<LlmModelCallEntity> {

    /**
     * 查询同一逻辑调用已使用的最大尝试序号。
     *
     * @param logicalCallId 逻辑调用标识
     * @return 最大尝试序号
     */
    @Select("""
            SELECT COALESCE(MAX(attempt_no), 0)
            FROM llm_model_calls
            WHERE logical_call_id = #{logicalCallId}
              AND deleted = 0
            """)
    int selectMaxAttempt(@Param("logicalCallId") String logicalCallId);

    /**
     * 按当前用户和白名单条件查询最近调用。
     *
     * @param userId 用户 ID
     * @param from 起始时间
     * @param to 结束时间
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param provider 供应商
     * @param model 模型
     * @param workflowType 工作流类型
     * @param callStatus 调用状态
     * @param offset 分页偏移
     * @param pageSize 分页大小
     * @return 最近调用列表
     */
    @Select("""
            <script>
            SELECT *
            FROM llm_model_calls
            WHERE user_id = #{userId}
              AND deleted = 0
              AND started_at &gt;= #{from}
              AND started_at &lt; #{to}
              <if test="workId != null">AND work_id = #{workId}</if>
              <if test="chapterId != null">AND chapter_id = #{chapterId}</if>
              <if test="provider != null and provider != ''">AND provider = #{provider}</if>
              <if test="model != null and model != ''">AND model = #{model}</if>
              <if test="workflowType != null and workflowType != ''">
                AND workflow_type = #{workflowType}
              </if>
              <if test="callStatus != null and callStatus != ''">AND call_status = #{callStatus}</if>
            ORDER BY started_at DESC, id DESC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<LlmModelCallEntity> selectRecent(
            @Param("userId") String userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("workId") Long workId,
            @Param("chapterId") Long chapterId,
            @Param("provider") String provider,
            @Param("model") String model,
            @Param("workflowType") String workflowType,
            @Param("callStatus") String callStatus,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    /**
     * 统计当前用户和白名单条件下的调用总数。
     *
     * @param userId 用户 ID
     * @param from 起始时间
     * @param to 结束时间
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param provider 供应商
     * @param model 模型
     * @param workflowType 工作流类型
     * @param callStatus 调用状态
     * @return 调用总数
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM llm_model_calls
            WHERE user_id = #{userId}
              AND deleted = 0
              AND started_at &gt;= #{from}
              AND started_at &lt; #{to}
              <if test="workId != null">AND work_id = #{workId}</if>
              <if test="chapterId != null">AND chapter_id = #{chapterId}</if>
              <if test="provider != null and provider != ''">AND provider = #{provider}</if>
              <if test="model != null and model != ''">AND model = #{model}</if>
              <if test="workflowType != null and workflowType != ''">
                AND workflow_type = #{workflowType}
              </if>
              <if test="callStatus != null and callStatus != ''">AND call_status = #{callStatus}</if>
            </script>
            """)
    long countRecent(
            @Param("userId") String userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("workId") Long workId,
            @Param("chapterId") Long chapterId,
            @Param("provider") String provider,
            @Param("model") String model,
            @Param("workflowType") String workflowType,
            @Param("callStatus") String callStatus);

    /**
     * 按服务端白名单维度汇总当前用户调用。
     *
     * @param userId 用户 ID
     * @param from 起始时间
     * @param to 结束时间
     * @param workId 作品 ID
     * @param provider 供应商
     * @param model 模型
     * @param workflowType 工作流类型
     * @param groupBy 聚合维度
     * @return 聚合行
     */
    @Select("""
            <script>
            SELECT
              <choose>
                <when test="groupBy == 'date'">DATE_FORMAT(started_at, '%Y-%m-%d')</when>
                <when test="groupBy == 'user'">user_id</when>
                <when test="groupBy == 'work'">CAST(work_id AS CHAR)</when>
                <when test="groupBy == 'model'">CONCAT(provider, '/', model)</when>
                <otherwise>workflow_type</otherwise>
              </choose> AS groupKey,
              COUNT(*) AS attemptCount,
              COUNT(DISTINCT logical_call_id) AS logicalCallCount,
              SUM(CASE WHEN call_status IN ('succeeded', 'completed') THEN 1 ELSE 0 END) AS successCount,
              SUM(CASE WHEN call_status = 'failed' THEN 1 ELSE 0 END) AS failureCount,
              SUM(CASE WHEN call_status = 'canceled' THEN 1 ELSE 0 END) AS canceledCount,
              SUM(CASE WHEN error_code = 'TIMEOUT' THEN 1 ELSE 0 END) AS timeoutCount,
              SUM(CASE WHEN error_code = 'RATE_LIMITED' THEN 1 ELSE 0 END) AS rateLimitedCount,
              COALESCE(SUM(input_tokens), 0) AS inputTokens,
              COALESCE(SUM(output_tokens), 0) AS outputTokens,
              COALESCE(SUM(total_tokens), 0) AS totalTokens,
              COALESCE(SUM(estimated_cost), 0) AS estimatedCost,
              SUM(CASE WHEN cost_status = 'unpriced' THEN 1 ELSE 0 END) AS unpricedCount,
              COALESCE(AVG(elapsed_millis), 0) AS averageElapsedMillis
            FROM llm_model_calls
            WHERE user_id = #{userId}
              AND deleted = 0
              AND started_at &gt;= #{from}
              AND started_at &lt; #{to}
              <if test="workId != null">AND work_id = #{workId}</if>
              <if test="provider != null and provider != ''">AND provider = #{provider}</if>
              <if test="model != null and model != ''">AND model = #{model}</if>
              <if test="workflowType != null and workflowType != ''">
                AND workflow_type = #{workflowType}
              </if>
            GROUP BY
              <choose>
                <when test="groupBy == 'date'">DATE_FORMAT(started_at, '%Y-%m-%d')</when>
                <when test="groupBy == 'user'">user_id</when>
                <when test="groupBy == 'work'">work_id</when>
                <when test="groupBy == 'model'">provider, model</when>
                <otherwise>workflow_type</otherwise>
              </choose>
            ORDER BY groupKey ASC
            </script>
            """)
    List<LlmCallAggregateRow> summarize(
            @Param("userId") String userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("workId") Long workId,
            @Param("provider") String provider,
            @Param("model") String model,
            @Param("workflowType") String workflowType,
            @Param("groupBy") String groupBy);
}
