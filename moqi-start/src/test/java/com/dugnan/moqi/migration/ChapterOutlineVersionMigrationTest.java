package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 验证章节大纲表补齐实体继承的乐观锁版本列。
 *
 * @author dgn
 */
class ChapterOutlineVersionMigrationTest {

    /**
     * 迁移必须为 chapter_outlines 增加非空且默认从零开始的 version 列。
     *
     * @throws Exception 资源读取失败
     */
    @Test
    void addsVersionColumnRequiredByMybatisPlusEntityMapping() throws Exception {
        var resource = new ClassPathResource(
                "db/migration/V12__add_chapter_outline_version.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE chapter_outlines")
                .contains("ADD COLUMN version INT NOT NULL DEFAULT 0");
    }
}
