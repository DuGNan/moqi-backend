CREATE TABLE chapter_prose_revisions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    parent_revision_id BIGINT NULL,
    source_generation_id BIGINT NULL,
    source_bounded_revision_id BIGINT NULL,
    source_snapshot_id BIGINT NULL,
    evaluation_report_id BIGINT NULL,
    revision_no INT NOT NULL,
    revision_origin VARCHAR(32) NOT NULL,
    revision_status VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prose_revision_no (chapter_id, revision_no),
    UNIQUE KEY uk_prose_revision_idempotency (chapter_id, idempotency_key),
    KEY idx_prose_revision_chapter_status (chapter_id, revision_status, revision_no),
    CONSTRAINT fk_prose_revision_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_prose_revision_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_prose_revision_parent FOREIGN KEY (parent_revision_id) REFERENCES chapter_prose_revisions (id),
    CONSTRAINT fk_prose_revision_generation FOREIGN KEY (source_generation_id) REFERENCES chapter_generations (id),
    CONSTRAINT fk_prose_revision_bounded FOREIGN KEY (source_bounded_revision_id) REFERENCES bounded_chapter_revisions (id),
    CONSTRAINT fk_prose_revision_snapshot FOREIGN KEY (source_snapshot_id) REFERENCES chapter_asset_source_snapshots (id),
    CONSTRAINT fk_prose_revision_report FOREIGN KEY (evaluation_report_id)
        REFERENCES chapter_generation_evaluation_reports (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE story_releases (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    parent_release_id BIGINT NULL,
    rollback_of_release_id BIGINT NULL,
    release_no INT NOT NULL,
    release_status VARCHAR(32) NOT NULL,
    current_marker TINYINT NULL,
    release_hash CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    confirmed_by VARCHAR(64) NOT NULL,
    confirmed_at DATETIME NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_story_release_no (work_id, release_no),
    UNIQUE KEY uk_story_release_idempotency (work_id, idempotency_key),
    UNIQUE KEY uk_story_release_current (work_id, current_marker),
    CONSTRAINT fk_story_release_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_story_release_parent FOREIGN KEY (parent_release_id) REFERENCES story_releases (id),
    CONSTRAINT fk_story_release_rollback FOREIGN KEY (rollback_of_release_id) REFERENCES story_releases (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE story_release_chapters (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    prose_revision_id BIGINT NOT NULL,
    chapter_no INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_story_release_chapter (release_id, chapter_id),
    UNIQUE KEY uk_story_release_order (release_id, chapter_no),
    KEY idx_story_release_revision (prose_revision_id),
    CONSTRAINT fk_story_release_chapter_release FOREIGN KEY (release_id) REFERENCES story_releases (id),
    CONSTRAINT fk_story_release_chapter_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_story_release_chapter_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_story_release_chapter_revision FOREIGN KEY (prose_revision_id)
        REFERENCES chapter_prose_revisions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE work_revision_workspaces (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    baseline_release_id BIGINT NULL,
    published_release_id BIGINT NULL,
    baseline_work_version INT NOT NULL,
    workspace_status VARCHAR(32) NOT NULL,
    current_marker TINYINT NULL,
    blocking_items_json JSON NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    abandoned_by VARCHAR(64) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workspace_idempotency (work_id, idempotency_key),
    UNIQUE KEY uk_workspace_current (work_id, current_marker),
    KEY idx_workspace_work_status (work_id, workspace_status, id),
    CONSTRAINT fk_workspace_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_workspace_baseline_release FOREIGN KEY (baseline_release_id) REFERENCES story_releases (id),
    CONSTRAINT fk_workspace_published_release FOREIGN KEY (published_release_id) REFERENCES story_releases (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE work_revision_workspace_chapters (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    prose_revision_id BIGINT NOT NULL,
    baseline_prose_revision_id BIGINT NULL,
    baseline_chapter_version INT NOT NULL,
    entry_status VARCHAR(32) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workspace_chapter (workspace_id, chapter_id),
    KEY idx_workspace_revision (prose_revision_id),
    CONSTRAINT fk_workspace_chapter_workspace FOREIGN KEY (workspace_id) REFERENCES work_revision_workspaces (id),
    CONSTRAINT fk_workspace_chapter_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_workspace_chapter_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_workspace_chapter_revision FOREIGN KEY (prose_revision_id) REFERENCES chapter_prose_revisions (id),
    CONSTRAINT fk_workspace_chapter_baseline_revision FOREIGN KEY (baseline_prose_revision_id)
        REFERENCES chapter_prose_revisions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE works
    ADD COLUMN current_story_release_id BIGINT NULL AFTER status,
    ADD KEY idx_works_current_story_release (current_story_release_id),
    ADD CONSTRAINT fk_works_current_story_release FOREIGN KEY (current_story_release_id) REFERENCES story_releases (id);

ALTER TABLE chapters
    ADD COLUMN current_prose_revision_id BIGINT NULL AFTER content,
    ADD KEY idx_chapters_current_prose_revision (current_prose_revision_id),
    ADD CONSTRAINT fk_chapters_current_prose_revision FOREIGN KEY (current_prose_revision_id)
        REFERENCES chapter_prose_revisions (id);
