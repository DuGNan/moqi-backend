package com.dugnan.moqi.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 验证 V48 补齐模型规划来源快照和正文修改提案结算字段。
 */
class ProseProposalContractMigrationTest {

    @Test
    void addsFrozenPlanningContextAndAppliedCandidateResult() throws IOException {
        String sql = new ClassPathResource("db/migration/V48__close_prose_proposal_contract.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("planning_context_json JSON NULL")
                .contains("applied_candidate_version INT NULL")
                .contains("applied_candidate_hash CHAR(64) NULL")
                .contains("idx_selection_assistance_proposal_gate")
                .doesNotContain("UPDATE chapter_plan_versions")
                .doesNotContain("package_status = 'applied'");
    }
}
