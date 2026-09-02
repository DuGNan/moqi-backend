package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 锁定已发布迁移的版本、文件名和内容，防止再次改号或改写。 */
class PublishedMigrationImmutabilityTest {

    private static final Map<String, String> PUBLISHED_MIGRATIONS = Map.of(
            "db/migration/V50__scope_prose_object_conversations.sql",
            "ed99d452f598401e4a647eab64906017b9e6dd3b140349e6c8673303d101674c",
            "db/migration/V51__bind_formal_prose_generation_basis.sql",
            "219d61e81264fd079c38696d9fe7aad62116a09fa667c5f88c3fdba9ddf0e4b4",
            "db/migration/V52__freeze_selection_assistance_history.sql",
            "da82fac1dfad99576ad2470153c285f59efac866328b99d481f2d381a42fbffd",
            "db/migration/V53__add_chapter_title_candidates.sql",
            "63390b191283ae973039f3fc7b273ff4a26b6c87543b7dd064192225d466871c");

    @Test
    void keepsPublishedMigrationSequenceImmutable() throws Exception {
        for (Map.Entry<String, String> migration : PUBLISHED_MIGRATIONS.entrySet()) {
            assertThat(sha256(migration.getKey())).as(migration.getKey()).isEqualTo(migration.getValue());
        }
    }

    private String sha256(String path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            input.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
