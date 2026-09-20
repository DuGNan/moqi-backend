package com.dugnan.moqi.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dugnan.moqi.impact.ProseImpactReleaseHook;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ConfirmCandidateRequest;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeCandidateEntity;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeExtractionBatchMapper;
import com.dugnan.moqi.knowledge.service.KnowledgeExtractionService;
import com.dugnan.moqi.knowledge.service.impl.ReleaseKnowledgeQuery;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 在指定隔离 MySQL 基线上验证生产知识发布钩子与事务恢复；预置候选不是模型验收。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/moqi_issue_184_v56?useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password="
})
@EnabledIfEnvironmentVariable(named = "MOQI_184_MYSQL_TEST", matches = "true")
class ReleaseKnowledgeMysqlTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KnowledgeExtractionService extraction;
    @Autowired private StoryKnowledgeCandidateMapper candidates;
    @Autowired private ProseImpactReleaseHook releaseHook;
    @Autowired private ReleaseKnowledgeQuery query;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @Autowired private StoryKnowledgeExtractionBatchMapper batches;
    private Long baselineReleaseId;
    private Long fixtureBatchId;
    private Map<String, List<Map<String, Object>>> persistedRows;

    @BeforeEach
    void isolatedDatabaseOnly() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("moqi_issue_184_v56");
        baselineReleaseId = jdbc.queryForObject("SELECT current_story_release_id FROM works WHERE id=1", Long.class);
        assertThat(baselineReleaseId).as("隔离验收作品必须存在当前发布版本").isNotNull();
        persistedRows = snapshotPersistedRows();
    }

    @AfterEach
    void leavesExistingKnowledgeAndReleaseDataUnchanged() {
        if (persistedRows == null) {
            return;
        }
        assertThat(snapshotPersistedRows()).isEqualTo(persistedRows);
    }

    private Map<String, List<Map<String, Object>>> snapshotPersistedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        // 固定的小型隔离验收库：逐表比较完整行，含软删除资产与历史快照，排除自增序列。
        for (String table : List.of("works", "story_knowledge_candidates", "story_knowledge_extraction_batches",
                "story_releases", "story_release_chapters", "story_release_knowledge_snapshots",
                "chapter_summaries", "setting_entries", "chapter_key_events", "foreshadowing_items")) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return rows;
    }

    @Test
    void confirmsFourTypesWithoutEarlyWritesThenPublishesAndRestoresContent() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            status.setRollbackOnly();
            String before = summary();
            long initialSettings = count("setting_entries");
            long initialEvents = count("chapter_key_events");
            long initialForeshadowing = count("foreshadowing_items");
            seedFourDecisions();
            assertThat(summary()).isEqualTo(before);
            assertThat(count("setting_entries")).isEqualTo(initialSettings);
            assertThat(count("chapter_key_events")).isEqualTo(initialEvents);
            assertThat(count("foreshadowing_items")).isEqualTo(initialForeshadowing);
            Long next = preparingRelease();
            releaseHook.activateRelease(1L, next, baselineReleaseId, null);
            assertThat(summary()).isEqualTo("QA184_NEW_SUMMARY");
            assertThat(count("setting_entries")).isEqualTo(initialSettings + 1);
            assertThat(count("chapter_key_events")).isEqualTo(initialEvents + 1);
            assertThat(count("foreshadowing_items")).isEqualTo(initialForeshadowing + 1);
            assertThat(query.get(1L, next).items()).anyMatch(item -> item.content().containsValue("QA184_NEW_SUMMARY"));
            Long restored = preparingRelease();
            releaseHook.activateRelease(1L, restored, next, baselineReleaseId);
            assertThat(summary()).isEqualTo(before);
            assertThat(count("setting_entries")).isEqualTo(initialSettings);
            assertThat(count("chapter_key_events")).isEqualTo(initialEvents);
            assertThat(count("foreshadowing_items")).isEqualTo(initialForeshadowing);
            // 回退后中间版本的内容仍可查询，不能被当前投影覆盖。
            assertThat(query.get(1L, next).items()).anyMatch(item -> item.content().containsValue("QA184_NEW_SUMMARY"));
            // 再回到中间历史版本：其 create 资产此时已软删除，必须完整重新激活。
            releaseHook.activateRelease(1L, preparingRelease(), restored, next);
            assertThat(summary()).isEqualTo("QA184_NEW_SUMMARY");
            assertThat(count("setting_entries")).isEqualTo(initialSettings + 1);
            assertThat(count("chapter_key_events")).isEqualTo(initialEvents + 1);
            assertThat(count("foreshadowing_items")).isEqualTo(initialForeshadowing + 1);
        });
    }

    @Test
    void failureAfterKnowledgeActivationRollsBackSnapshotsAndProjection() {
        String before = summary();
        long releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM story_releases", Long.class);
        long snapshotCount = jdbc.queryForObject("SELECT COUNT(*) FROM story_release_knowledge_snapshots", Long.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            seedFourDecisions();
            Long next = preparingRelease();
            releaseHook.activateRelease(1L, next, baselineReleaseId, null);
            assertThat(summary()).isEqualTo("QA184_NEW_SUMMARY");
            jdbc.update("UPDATE works SET current_story_release_id=? WHERE id=1", next);
            throw new IllegalStateException("QA184 injected failure after knowledge activation");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("QA184 injected failure after knowledge activation");
        assertThat(summary()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_releases", Long.class)).isEqualTo(releaseCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_release_knowledge_snapshots", Long.class))
                .isEqualTo(snapshotCount);
        assertThat(jdbc.queryForObject("SELECT current_story_release_id FROM works WHERE id=1", Long.class))
                .isEqualTo(baselineReleaseId);
    }

    @Test
    void changedDecisionTargetRejectsPublicationAndCrossWorkSnapshotRead() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            status.setRollbackOnly();
            seedFourDecisions();
            jdbc.update("UPDATE chapter_summaries SET version=version+1 WHERE id=1");
            assertThatThrownBy(() -> releaseHook.activateRelease(1L, preparingRelease(), baselineReleaseId, null))
                    .isInstanceOf(com.dugnan.moqi.common.exception.BusinessException.class);
            assertThat(summary()).isNotEqualTo("QA184_NEW_SUMMARY");
            assertThatThrownBy(() -> query.get(2L, baselineReleaseId))
                    .isInstanceOf(com.dugnan.moqi.common.exception.BusinessException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"merge", "replace"})
    void stagesUpdatesAndRestoresAllExistingKnowledge(String resolution) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            status.setRollbackOnly();
            prepareFixtureBatch();
            jdbc.update("INSERT INTO setting_entries(work_id,setting_type,name,content,entry_status) "
                    + "VALUES(1,'place','QA184_BASE','QA184_OLD_SETTING','active')");
            Long setting = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO chapter_key_events(work_id,chapter_id,event_title,event_content,event_type) "
                    + "VALUES(1,1,'QA184_BASE','QA184_OLD_EVENT','plot')");
            Long event = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO foreshadowing_items(work_id,source_chapter_id,title,description,status) "
                    + "VALUES(1,1,'QA184_BASE','QA184_OLD_HINT','planted')");
            Long hint = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            seed("setting", resolution, setting,
                    Map.of("settingType", "place", "name", "QA184_BASE", "content", "QA184_UPDATED_SETTING"));
            seed("key_event", resolution, event, Map.of("title", "QA184_BASE", "content", "QA184_UPDATED_EVENT",
                    "eventType", "plot", "occurredOrder", 2, "relatedSettingIds", List.of(),
                    "relatedForeshadowingIds", List.of()));
            seed("foreshadowing", resolution, hint,
                    Map.of("action", "seed", "title", "QA184_BASE", "description", "QA184_UPDATED_HINT"));
            assertThat(jdbc.queryForObject("SELECT content FROM setting_entries WHERE id=?", String.class, setting))
                    .isEqualTo("QA184_OLD_SETTING");
            Long next = preparingRelease();
            releaseHook.activateRelease(1L, next, baselineReleaseId, null);
            assertThat(query.get(1L, next).items()).anyMatch(item -> item.content().containsValue("QA184_UPDATED_SETTING"))
                    .anyMatch(item -> item.content().containsValue("QA184_UPDATED_EVENT"))
                    .anyMatch(item -> item.content().containsValue("QA184_UPDATED_HINT"));
            releaseHook.activateRelease(1L, preparingRelease(), next, baselineReleaseId);
            assertThat(jdbc.queryForObject("SELECT content FROM setting_entries WHERE id=?", String.class, setting))
                    .isEqualTo("QA184_OLD_SETTING");
            assertThat(jdbc.queryForObject("SELECT event_content FROM chapter_key_events WHERE id=?", String.class, event))
                    .isEqualTo("QA184_OLD_EVENT");
            assertThat(jdbc.queryForObject("SELECT description FROM foreshadowing_items WHERE id=?", String.class, hint))
                    .isEqualTo("QA184_OLD_HINT");
        });
    }

    @Test
    void republishesSummaryAfterEmptySnapshotRollbackWithoutUniqueKeyCollision() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            status.setRollbackOnly();
            prepareFixtureBatch();
            jdbc.update("UPDATE chapter_summaries SET deleted=1 WHERE id=1");
            seed("chapter_summary", "create", Map.of("summary", "QA184_RESTORED_SUMMARY",
                    "characterChanges", List.of(), "openQuestions", List.of()));
            Long next = preparingRelease();
            releaseHook.activateRelease(1L, next, baselineReleaseId, null);
            assertThat(summary()).isEqualTo("QA184_RESTORED_SUMMARY");
            assertThat(jdbc.queryForObject("SELECT deleted FROM chapter_summaries WHERE id=1", Integer.class)).isZero();
            releaseHook.activateRelease(1L, preparingRelease(), next, baselineReleaseId);
            assertThat(jdbc.queryForObject("SELECT deleted FROM chapter_summaries WHERE id=1", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void releaseBatchSelectionMatchesLatestGateEvenWhenLatestIsNotReady() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            status.setRollbackOnly();
            prepareFixtureBatch();
            Long next = preparingRelease();
            assertThat(batches.forRelease(1L, next, baselineReleaseId)).extracting("id").containsExactly(fixtureBatchId);
            var newer = batches.selectById(fixtureBatchId);
            newer.setId(null);
            newer.setExtractorVersion("qa184-v2");
            newer.setIdempotencyKey("qa184-" + UUID.randomUUID());
            newer.setBatchStatus("running");
            batches.insert(newer);
            assertThat(batches.forRelease(1L, next, baselineReleaseId)).isEmpty();
            newer.setBatchStatus("ready");
            batches.updateById(newer);
            assertThat(batches.forRelease(1L, next, baselineReleaseId)).extracting("id").containsExactly(newer.getId());
        });
    }

    private void prepareFixtureBatch() {
        // 只复用验收正文及其指纹，不共享 HTTP 验收批次或决策；调用方必须处于回滚事务中。
        var fixture = batches.selectById(5L);
        assertThat(fixture).as("隔离库必须预置 revision 11 的抽取批次模板 5").isNotNull();
        assertThat(fixture.getSourceProseRevisionId()).isEqualTo(11L);
        fixture.setId(null);
        fixture.setSourceStoryReleaseId(baselineReleaseId);
        fixture.setAiTaskId(null);
        fixture.setAgentRunId(null);
        fixture.setIdempotencyKey("qa184-" + UUID.randomUUID());
        fixture.setBatchStatus("ready");
        fixture.setCandidateCount(0);
        fixture.setErrorCode(null);
        fixture.setVersion(0);
        batches.insert(fixture);
        fixtureBatchId = fixture.getId();
    }

    private void seedFourDecisions() {
        prepareFixtureBatch();
        seed("chapter_summary", "replace", Map.of("summary", "QA184_NEW_SUMMARY",
                "characterChanges", List.of(), "openQuestions", List.of()));
        seed("setting", "create", Map.of("settingType", "place", "name", "QA184_PLACE", "content", "QA184_PLACE_DETAIL"));
        seed("key_event", "create", Map.of("title", "QA184_EVENT", "content", "QA184_EVENT_DETAIL",
                "eventType", "plot", "occurredOrder", 1, "relatedSettingIds", List.of(), "relatedForeshadowingIds", List.of()));
        seed("foreshadowing", "create", Map.of("action", "seed", "title", "QA184_HINT", "description", "QA184_HINT_DETAIL"));
    }

    private void seed(String type, String resolution, Map<String, Object> payload) {
        seed(type, resolution, null, payload);
    }

    private void seed(String type, String resolution, Long targetId, Map<String, Object> payload) {
        StoryKnowledgeCandidateEntity candidate = new StoryKnowledgeCandidateEntity();
        candidate.setBatchId(fixtureBatchId);
        candidate.setWorkId(1L);
        candidate.setChapterId(1L);
        candidate.setGenerationId(4L);
        candidate.setCandidateKey("qa184-" + UUID.randomUUID());
        candidate.setCandidateType(type);
        candidate.setCandidateStatus("pending");
        try {
            candidate.setPayloadJson(json.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
        candidate.setEvidenceStartOffset(0);
        candidate.setEvidenceEndOffset(5);
        candidate.setEvidenceText("Issue");
        candidate.setCandidateFingerprint("0".repeat(64));
        candidate.setDeleted(0);
        candidate.setVersion(0);
        candidates.insert(candidate);
        ConfirmCandidateRequest request = new ConfirmCandidateRequest(0, resolution, targetId, null);
        var first = extraction.confirm(candidate.getId(), request);
        var repeated = extraction.confirm(candidate.getId(), request);
        assertThat(first.candidateStatus()).isEqualTo("confirmed");
        assertThat(first.targetId()).isNull();
        assertThat(repeated).isEqualTo(first);
    }

    private Long preparingRelease() {
        jdbc.update("""
                INSERT INTO story_releases
                (work_id,parent_release_id,release_no,release_status,release_hash,idempotency_key,confirmed_by,confirmed_at)
                SELECT 1,?,COALESCE(MAX(release_no),0)+1,'preparing',REPEAT('0',64),?,'qa184',NOW()
                FROM story_releases WHERE work_id=1
                """, baselineReleaseId, "qa184-" + UUID.randomUUID());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO story_release_chapters(work_id,release_id,chapter_id,prose_revision_id,chapter_no,content_hash)
                SELECT 1,?,chapter_id,id,1,content_hash FROM chapter_prose_revisions WHERE id=11
                """, id);
        return id;
    }

    private String summary() {
        return jdbc.queryForObject("SELECT summary FROM chapter_summaries WHERE id=1", String.class);
    }

    private long count(String table) {
        if (!List.of("setting_entries", "chapter_key_events", "foreshadowing_items").contains(table)) {
            throw new IllegalArgumentException("Only fixed QA table names are allowed");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE work_id=1 AND deleted=0", Long.class);
    }
}
