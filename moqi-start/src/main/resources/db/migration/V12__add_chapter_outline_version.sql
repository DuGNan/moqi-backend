ALTER TABLE chapter_outlines
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER deleted;
