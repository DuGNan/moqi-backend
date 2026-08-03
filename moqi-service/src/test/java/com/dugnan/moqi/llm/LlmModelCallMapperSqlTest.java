package com.dugnan.moqi.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.mapper.LlmModelCallMapper;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 检查模型调用聚合 SQL 的尝试计数语义和参数化边界。
 */
class LlmModelCallMapperSqlTest {

    @Test
    void aggregatesAttemptsSeparatelyFromLogicalCallsWithoutStringSubstitution() throws Exception {
        Select select = LlmModelCallMapper.class
                .getMethod(
                        "summarize",
                        String.class,
                        java.time.LocalDateTime.class,
                        java.time.LocalDateTime.class,
                        Long.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", Arrays.asList(select.value()));

        assertThat(sql)
                .contains("COUNT(*) AS attemptCount")
                .contains("COUNT(DISTINCT logical_call_id) AS logicalCallCount")
                .contains("error_code = 'TIMEOUT'")
                .contains("error_code = 'RATE_LIMITED'")
                .doesNotContain("${");
    }
}
