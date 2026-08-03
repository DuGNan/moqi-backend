package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 验证 V20 建立调用维度、必要索引和版本化模型单价约束。
 */
class LlmObservabilityMigrationTest {

    @Test
    void createsObservableCallAndPriceSchema() throws Exception {
        String sql = new ClassPathResource("db/migration/V20__add_llm_call_observability.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("logical_call_id")
                .contains("attempt_no")
                .contains("uk_llm_model_calls_logical_attempt")
                .contains("idx_llm_model_calls_user_time")
                .contains("idx_llm_model_calls_work_time")
                .contains("idx_llm_model_calls_model_time")
                .contains("idx_llm_model_calls_workflow_time")
                .contains("CREATE TABLE llm_model_prices")
                .contains("input_cache_miss_price_per_million")
                .contains("estimated_cost")
                .contains("fk_llm_model_calls_price_version_id");
    }
}
