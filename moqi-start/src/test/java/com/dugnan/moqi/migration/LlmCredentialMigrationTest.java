package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 验证 V9 只创建独立凭据结构且不包含任何主密钥或明文。
 */
class LlmCredentialMigrationTest {

    @Test
    void createsEncryptedCredentialTableWithoutEmbeddedMasterKey() throws Exception {
        var resource = new ClassPathResource("db/migration/V9__add_llm_credentials.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS llm_credentials")
                .contains("ciphertext LONGTEXT NOT NULL")
                .contains("nonce VARCHAR(64) NOT NULL")
                .contains("key_id VARCHAR(128) NOT NULL")
                .contains("uk_llm_credentials_identity")
                .doesNotContain("apiKey", "MOQI_CREDENTIAL_KEYS", "AES/GCM");
    }
}
