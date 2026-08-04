package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 校验章节资产来源链迁移包含快照、幂等审计和历史数据复核标记。
 */
class ChapterAssetSourceChainMigrationTest {
    @Test
    void createsSnapshotsAuditsAndLegacyReviewMarkers() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V24__add_chapter_asset_source_chain.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(sql).contains("chapter_asset_source_snapshots", "chapter_asset_validity_audits",
                "uk_asset_validity_event", "source_snapshot_id", "validity_status",
                "legacy_source_incomplete");
    }
}
