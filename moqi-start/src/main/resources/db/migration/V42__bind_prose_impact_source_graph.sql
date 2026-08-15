ALTER TABLE prose_revision_impact_reports
    ADD COLUMN source_graph_fingerprint CHAR(64) NULL AFTER input_fingerprint;

ALTER TABLE prose_revision_fact_changes
    ADD COLUMN affected_chapter_ids_json JSON NULL AFTER direct_dependency;
