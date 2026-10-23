package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dugnan.moqi.chapter.dto.SceneGenerationModels.CreateSceneGenerationRequest;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationPromptCompiler;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-10-20
 * @description 用隔离 H2 与真实 MyBatis 映射验证稳定对象写回的事务、快照和恢复契约。
 */
class ProseGenerationTargetTransactionTest {

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ChapterMapper chapters;
    private ChapterProseCandidateMapper candidates;
    private ChapterGenerationMapper generations;
    private ProseGenerationTargetService service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        createTable("chapters", ChapterEntity.class);
        createTable("chapter_prose_candidates", ChapterProseCandidateEntity.class);
        createTable("chapter_generations", ChapterGenerationEntity.class);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ChapterMapper.class);
        configuration.addMapper(ChapterProseCandidateMapper.class);
        configuration.addMapper(ChapterGenerationMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        chapters = session.getMapper(ChapterMapper.class);
        candidates = session.getMapper(ChapterProseCandidateMapper.class);
        generations = session.getMapper(ChapterGenerationMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = new ProseGenerationTargetService(chapters, candidates, generations, new ObjectMapper());
        jdbc.update("INSERT INTO chapters (id, work_id, content, version, deleted) VALUES (423, 94, '', 0, 0)");
    }

    @Test
    void reservesOneCandidateAndWritesSameIdExactlyOnce() {
        ChapterGenerationEntity generation = create("new_candidate", "formal:423", 0, "");
        String objectId = generation.getTargetObjectId();
        assertThat(countCandidates()).isEqualTo(1);
        assertThat(service.tasks(423L).get(0).status()).isEqualTo("queued");
        finish(generation, "完整的新候选正文");
        transaction.executeWithoutResult(status -> service.complete(generation));
        assertThat(countCandidates()).isEqualTo(1);
        ChapterProseCandidateEntity candidate = candidates.selectById(candidateId(objectId));
        assertThat(candidate.getContent()).isEqualTo("完整的新候选正文");
        assertThat(candidate.getVersion()).isEqualTo(1);
        assertThat(candidate.getSourceGenerationId()).isEqualTo(generation.getId());
        assertThat(candidate.getQualityGenerationId()).isEqualTo(generation.getId());
        assertThat(candidate.getQualityRequestStatus()).isEqualTo("pending");
        assertThat(service.tasks(423L).get(0).status()).isEqualTo("succeeded");
        assertThat(generations.findPendingTargetWritebacks()).hasSize(1);
    }

    @Test
    void replacesCandidateAndRetainsImmutableOldBatch() {
        ChapterGenerationEntity first = create("new_candidate", "formal:423", 0, "");
        finish(first, "旧稿");
        ChapterGenerationEntity second = create("replace_current", first.getTargetObjectId(), 1, "旧稿");
        assertThat(countCandidates()).isEqualTo(1);
        assertThat(generations.selectById(second.getId()).getTargetContentSnapshot()).isEqualTo("旧稿");
        finish(second, "新稿");
        assertThat(candidates.selectById(candidateId(first.getTargetObjectId())).getVersion()).isEqualTo(2);
        assertThat(generations.selectById(first.getId()).getGeneratedContent()).isEqualTo("旧稿");
        assertThat(service.tasks(423L)).hasSize(1);
        assertThat(service.tasks(423L).get(0).generationId()).isEqualTo(second.getId());
    }

    @Test
    void replacesFormalWithoutCreatingCandidateAndBindsBasis() {
        ChapterGenerationEntity generation = create("replace_current", "formal:423", 0, "");
        finish(generation, "正式正文");
        ChapterEntity chapter = chapters.selectById(423L);
        assertThat(chapter.getContent()).isEqualTo("正式正文");
        assertThat(chapter.getVersion()).isEqualTo(1);
        assertThat(chapter.getFormalSourceGenerationId()).isEqualTo(generation.getId());
        assertThat(countCandidates()).isZero();
    }

    @Test
    void doesNotOverwriteConcurrentCandidateEdit() {
        ChapterGenerationEntity first = create("new_candidate", "formal:423", 0, "");
        finish(first, "旧稿");
        ChapterGenerationEntity second = create("replace_current", first.getTargetObjectId(), 1, "旧稿");
        jdbc.update("UPDATE chapter_prose_candidates SET content = '作者编辑', content_hash = ?, "
                + "version = version + 1 WHERE id = ?", hash("作者编辑"), candidateId(first.getTargetObjectId()));
        finish(second, "迟到生成");
        assertThat(candidates.selectById(candidateId(first.getTargetObjectId())).getContent()).isEqualTo("作者编辑");
        assertThat(service.tasks(423L).get(0).status()).isEqualTo("conflict");
        assertThat(service.tasks(423L).get(0).originalContentPreserved()).isFalse();
        assertThat(generations.selectById(second.getId()).getGeneratedContent()).isEqualTo("迟到生成");
        assertThat(service.canEvaluate(second.getId())).isFalse();
    }

    @Test
    void publishedFormalRejectsReplacementAndLateWriteback() {
        ChapterGenerationEntity generation = create("replace_current", "formal:423", 0, "");
        jdbc.update("UPDATE chapters SET current_prose_revision_id = 7, content = '已发布' WHERE id = 423");
        finish(generation, "迟到生成");
        assertThat(chapters.selectById(423L).getContent()).isEqualTo("已发布");
        assertThat(service.tasks(423L).get(0).status()).isEqualTo("conflict");
        assertThatThrownBy(() -> create("replace_current", "formal:423", 0, "已发布"))
                .isInstanceOf(BusinessException.class);
        assertThat(create("new_candidate", "formal:423", 0, "已发布").getTargetObjectId())
                .startsWith("candidate:");
    }

    @Test
    void failedAndCanceledTaskKeepOriginalAndCanBeRegenerated() {
        jdbc.update("UPDATE chapters SET content = '旧稿' WHERE id = 423");
        ChapterGenerationEntity first = create("replace_current", "formal:423", 0, "旧稿");
        jdbc.update("UPDATE chapter_generations SET generation_status = 'failed' WHERE id = ?", first.getId());
        transaction.executeWithoutResult(status -> service.complete(first));
        assertThat(service.tasks(423L).get(0).status()).isEqualTo("failed");
        assertThat(service.tasks(423L).get(0).originalContentPreserved()).isTrue();
        ChapterGenerationEntity retry = create("replace_current", "formal:423", 0, "旧稿");
        jdbc.update("UPDATE chapter_generations SET generation_status = 'canceled' WHERE id = ?", retry.getId());
        transaction.executeWithoutResult(status -> service.complete(retry));
        assertThat(chapters.selectById(423L).getContent()).isEqualTo("旧稿");
        assertThat(service.tasks(423L).get(0).status()).isEqualTo("canceled");
        assertThat(service.tasks(423L).get(0).originalContentPreserved()).isTrue();
    }

    @Test
    void rejectsEmptyOutputAndConcurrentTaskWithoutChangingContent() {
        ChapterGenerationEntity generation = create("replace_current", "formal:423", 0, "");
        assertThatThrownBy(() -> create("replace_current", "formal:423", 0, ""))
                .isInstanceOf(BusinessException.class);
        finish(generation, "   ");
        assertThat(chapters.selectById(423L).getContent()).isEmpty();
        assertThat(service.tasks(423L).get(0).status()).isEqualTo("failed");
        assertThat(service.tasks(423L).get(0).originalContentPreserved()).isFalse();
    }

    @Test
    void serializesTwoConcurrentRequestsForSameObject() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> attempt = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                create("replace_current", "formal:423", 0, "");
                return true;
            } catch (BusinessException expectedConflict) {
                return false;
            }
        };
        try {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(service.tasks(423L)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rollsBackReservedCandidateWhenCreationFails() {
        transaction.executeWithoutResult(status -> {
            ChapterEntity chapter = service.lockChapter(423L);
            service.prepare(chapter, generation(), request("new_candidate", "formal:423", 0, "", null));
            status.setRollbackOnly();
        });
        assertThat(countCandidates()).isZero();
        assertThat(service.tasks(423L)).isEmpty();
    }

    @Test
    void freezesCandidateReferenceIntoActualPromptAndRejectsForeignReference() {
        ChapterGenerationEntity first = create("new_candidate", "formal:423", 0, "");
        finish(first, "参考这份候选");
        ChapterGenerationEntity next = transaction.execute(status -> {
            ChapterGenerationEntity generation = generation();
            service.prepare(service.lockChapter(423L), generation,
                    request("new_candidate", "formal:423", 0, "", first.getTargetObjectId()));
            generations.insert(generation);
            return generation;
        });
        assertThat(next.getBasisSnapshotJson()).contains("参考这份候选").contains(first.getTargetObjectId());
        assertThat(new ChapterGenerationPromptCompiler(null, null, null, new ObjectMapper())
                .compileWholeChapter(next, 3000)
                .messages().toString()).contains("参考这份候选");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.prepare(
                service.lockChapter(423L), generation(),
                request("new_candidate", "formal:423", 0, "", "formal:999"))))
                .isInstanceOf(BusinessException.class);
    }

    private ChapterGenerationEntity create(String mode, String objectId, int version, String content) {
        return transaction.execute(status -> {
            ChapterGenerationEntity generation = generation();
            service.prepare(service.lockChapter(423L), generation, request(mode, objectId, version, content, null));
            generations.insert(generation);
            return generation;
        });
    }

    private void finish(ChapterGenerationEntity generation, String content) {
        jdbc.update("UPDATE chapter_generations SET generation_status = 'preview', generated_content = ? WHERE id = ?",
                content, generation.getId());
        // A freshly constructed service models restart without in-memory task state.
        ProseGenerationTargetService recovered = new ProseGenerationTargetService(
                chapters, candidates, generations, new ObjectMapper());
        assertThat(generations.findPendingTargetWritebacks()).isNotEmpty();
        transaction.executeWithoutResult(status -> recovered.complete(generation));
    }

    private ChapterGenerationEntity generation() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setWorkId(94L);
        generation.setChapterId(423L);
        generation.setSelectionMode("all");
        generation.setGenerationStatus("queued");
        generation.setBasisSnapshotJson(
                "{\"chapterGenerationBrief\":{\"content\":\"本章目标\", \"fingerprint\":\"frozen\"}}");
        generation.setDeleted(0);
        generation.setVersion(0);
        return generation;
    }

    private CreateSceneGenerationRequest request(String mode, String objectId, int version,
            String content, String reference) {
        return new CreateSceneGenerationRequest(null, "all", null, List.of(), null, UUID.randomUUID().toString(),
                "medium", null, null, null, null, false, mode, objectId, version, hash(content), reference);
    }

    private int countCandidates() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chapter_prose_candidates", Integer.class);
    }

    private Long candidateId(String objectId) {
        return Long.valueOf(objectId.substring("candidate:".length()));
    }

    private String hash(String content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void createTable(String table, Class<?> entityType) {
        List<String> columns = new ArrayList<>();
        for (Class<?> type = entityType; type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
                String sqlType = field.getType() == Long.class ? "BIGINT"
                        : field.getType() == Integer.class ? "INT"
                        : field.getType() == String.class ? "VARCHAR(100000)" : "TIMESTAMP";
                columns.add("id".equals(name) ? "id BIGINT AUTO_INCREMENT PRIMARY KEY" : name + " " + sqlType);
            }
        }
        jdbc.execute("CREATE TABLE " + table + " (" + String.join(", ", columns) + ")");
    }
}
