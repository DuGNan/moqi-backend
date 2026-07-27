package com.dugnan.moqi.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 在应用启动后执行幂等旧模型明文凭据迁移。
 */
@Component
public class LegacyModelCredentialMigrationRunner implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LegacyModelCredentialMigrationRunner.class);

    private final LegacyModelCredentialMigrationService migrationService;

    public LegacyModelCredentialMigrationRunner(
            LegacyModelCredentialMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int migrated = migrationService.migrate();
        if (migrated > 0) {
            LOGGER.info("旧模型凭据迁移完成，migratedCount={}", migrated);
        }
    }
}
