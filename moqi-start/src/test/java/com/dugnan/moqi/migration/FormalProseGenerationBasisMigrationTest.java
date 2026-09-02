package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-27
 * @description 验证 V51 为正式正文建立可追溯的冻结生成依据关联。
 */
class FormalProseGenerationBasisMigrationTest {

    @Test
    void bindsFormalProseToGenerationSourcesWithoutInventingCurrentMaterials() throws Exception {
        String sql = new String(new ClassPathResource(
                "db/migration/V51__bind_formal_prose_generation_basis.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("formal_source_generation_id")
                .contains("chapter_prose_revisions")
                .contains("chapter_prose_candidate_adoptions")
                .contains("generation_status = 'accepted'")
                .doesNotContain("basis_snapshot_json =");
    }
}
