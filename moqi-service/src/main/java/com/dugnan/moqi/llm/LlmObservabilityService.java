package com.dugnan.moqi.llm;

import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallPage;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallQuery;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallSummary;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmSummaryQuery;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 定义当前用户模型调用明细和聚合观测查询端口。
 */
public interface LlmObservabilityService {

    /**
     * 分页查询当前用户的最近模型调用。
     *
     * @param query 查询条件
     * @return 调用分页
     */
    LlmCallPage list(LlmCallQuery query);

    /**
     * 按白名单维度聚合当前用户的模型调用。
     *
     * @param query 聚合条件
     * @return 调用汇总
     */
    LlmCallSummary summarize(LlmSummaryQuery query);
}
