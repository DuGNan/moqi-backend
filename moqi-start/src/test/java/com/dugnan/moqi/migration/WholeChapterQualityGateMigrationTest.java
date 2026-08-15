package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证整章质量门禁迁移绑定正文、来源版本与独立模型调用。
 */
class WholeChapterQualityGateMigrationTest {

    @Test
    void addsTraceableWholeChapterEvaluationBindings() throws Exception {
        String sql = new ClassPathResource("db/migration/V38__add_whole_chapter_quality_gate.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("content_hash CHAR(64)")
                .contains("brief_fingerprint CHAR(64)")
                .contains("source_fingerprint CHAR(64)")
                .contains("model_call_id BIGINT")
                .contains("error_code VARCHAR(64)")
                .contains("FOREIGN KEY (model_call_id) REFERENCES llm_model_calls (id)")
                .doesNotContain("UPDATE chapter_generations");
    }
}
