package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 验证首版章纲候选类型、幂等约束与可空基础大纲迁移。
 */
class InitialOutlineCandidateMigrationTest {

    /**
     * V18 必须兼容旧调整候选并允许首版候选不绑定正式大纲。
     *
     * @throws Exception 资源读取失败
     */
    @Test
    void extendsCandidatesForInitialOutlineFlow() throws Exception {
        var resource = new ClassPathResource(
                "db/migration/V18__support_initial_outline_candidates.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("candidate_type VARCHAR(32) NOT NULL DEFAULT 'adjustment'")
                .contains("idempotency_key VARCHAR(128) NULL")
                .contains("MODIFY COLUMN base_outline_id BIGINT NULL")
                .contains("MODIFY COLUMN base_outline_revision INT NULL")
                .contains("MODIFY COLUMN base_outline_content JSON NULL")
                .contains("UNIQUE KEY uk_outline_candidates_chapter_idempotency");
    }
}
