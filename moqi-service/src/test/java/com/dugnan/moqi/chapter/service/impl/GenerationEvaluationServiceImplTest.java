package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationFinding;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.CreateEvaluationRequest;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationRevisionCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
import com.dugnan.moqi.context.entity.StoryContextSnapshotEntity;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.context.mapper.StoryContextSnapshotMapper;
import com.dugnan.moqi.sourcechain.entity.ChapterAssetSourceSnapshotEntity;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 验证正文评价的冻结来源越权拒绝和来源过期保护。
 */
class GenerationEvaluationServiceImplTest {

    @Test
    void resolvesWholeChapterAssetSnapshotToRealStoryContextId() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.createReport(
                "{\"chapterGenerationBrief\":{\"fingerprint\":\"brief-a\"}}",
                fixture.context("context-a", "{\"facts\":[]}"));

        assertThat(report.getContextSnapshotId()).isEqualTo(31L);
        assertThat(report.getSourceSnapshotJson())
                .contains("\"assetSourceSnapshotId\":21")
                .contains("\"contextSnapshotId\":31");
        verify(fixture.contextSnapshotMapper, never()).selectById(21L);
    }

    @Test
    void rejectsWholeChapterEvaluationWhenAssetSnapshotIsMissing() {
        Fixture fixture = new Fixture();
        ChapterGenerationEntity generation = fixture.generation();
        generation.setSourceSnapshotId(21L);
        when(fixture.generationMapper.selectById(3L)).thenReturn(generation);
        when(fixture.assetSourceSnapshotMapper.selectById(21L)).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.create(
                12L, 3L, new CreateEvaluationRequest(null, "missing-asset")))
                .isInstanceOf(BusinessException.class);
        verify(fixture.reportMapper, never()).insert(
                org.mockito.ArgumentMatchers.any(ChapterGenerationEvaluationReportEntity.class));
    }

    @Test
    void rejectsAssetSnapshotBelongingToAnotherGenerationInSameChapter() {
        Fixture fixture = new Fixture();
        ChapterGenerationEntity generation = fixture.generation();
        generation.setSourceSnapshotId(21L);
        ChapterAssetSourceSnapshotEntity wrongAsset = fixture.assetSnapshot();
        wrongAsset.setAssetId(999L);
        when(fixture.generationMapper.selectById(3L)).thenReturn(generation);
        when(fixture.assetSourceSnapshotMapper.selectById(21L)).thenReturn(wrongAsset);

        assertThatThrownBy(() -> fixture.service.create(
                12L, 3L, new CreateEvaluationRequest(null, "wrong-generation-asset")))
                .isInstanceOf(BusinessException.class);
        verify(fixture.reportMapper, never()).insert(
                org.mockito.ArgumentMatchers.any(ChapterGenerationEvaluationReportEntity.class));
    }

    @Test
    void freezesBriefAndCompleteSourceForEvaluatorInput() throws Exception {
        Fixture fixture = new Fixture();
        StoryContextSnapshotEntity context = fixture.context("context-a", "{\"facts\":[\"原事实\"]}");
        ChapterGenerationEvaluationReportEntity report = fixture.createReport(
                "{\"chapterGenerationBrief\":{\"fingerprint\":\"brief-a\",\"content\":\"冻结 Brief 正文\"}}",
                context);

        String semanticSource = fixture.service.semanticSource(report.getId());
        com.fasterxml.jackson.databind.JsonNode source = new ObjectMapper().readTree(semanticSource);

        assertThat(source.at("/basisSnapshot/chapterGenerationBrief/content").asText())
                .isEqualTo("冻结 Brief 正文");
        assertThat(source.at("/contextSnapshotHash").asText()).isEqualTo("context-a");
        assertThat(report.getSourceFingerprint()).isEqualTo(fixture.hash(semanticSource));
    }

    @Test
    void marksReportStaleWhenBasisSnapshotChanges() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.createReport(
                "{\"chapterGenerationBrief\":{\"fingerprint\":\"brief-a\"}}",
                fixture.context("context-a", "{\"facts\":[]}"));
        fixture.currentGeneration.setBasisSnapshotJson(
                "{\"chapterGenerationBrief\":{\"fingerprint\":\"brief-b\"}}");

        assertThatThrownBy(() -> fixture.service.semanticSource(report.getId()))
                .isInstanceOf(BusinessException.class);
        fixture.assertMarkedStale();
    }

    @Test
    void marksReportStaleWhenContextSnapshotChanges() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.createReport(
                "{\"chapterGenerationBrief\":{\"fingerprint\":\"brief-a\"}}",
                fixture.context("context-a", "{\"facts\":[\"原事实\"]}"));
        when(fixture.contextSnapshotMapper.selectById(31L)).thenReturn(
                fixture.context("context-b", "{\"facts\":[\"变化事实\"]}"));

        assertThatThrownBy(() -> fixture.service.semanticSource(report.getId()))
                .isInstanceOf(BusinessException.class);
        fixture.assertMarkedStale();
    }

    @Test
    void reusesEvaluationForSameIdempotencyKeyAndFrozenInput() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.batchReport();
        report.setChapterId(12L);
        report.setWorkId(1L);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.generation());
        when(fixture.reportMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(report);

        fixture.service.create(12L, 3L, new CreateEvaluationRequest(null, "quality-1"));

        verify(fixture.taskMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<com.dugnan.moqi.chapter.entity.AiTaskEntity>any());
        verify(fixture.reportMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<ChapterGenerationEvaluationReportEntity>any());
    }

    @Test
    void recordsPassWithoutChangingPreviewStatus() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.batchReport();
        when(fixture.reportMapper.selectById(9L)).thenReturn(report);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.generation());

        fixture.service.complete(9L, List.of());

        assertReportConclusion(fixture, "pass");
        verify(fixture.generationMapper, never()).update(
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedEvaluationKeepsPreviewAndRecordsNeedsHuman() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.batchReport();
        when(fixture.reportMapper.selectById(9L)).thenReturn(report);

        fixture.service.fail(9L, "evaluation_timeout", "评价超时");

        assertReportConclusion(fixture, "needs_human");
        verify(fixture.generationMapper, never()).update(
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsWarningWithoutChangingPreviewStatus() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.batchReport();
        when(fixture.reportMapper.selectById(9L)).thenReturn(report);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.generation());
        EvaluationFinding warning = new EvaluationFinding("style-1", "style", "warning", 0.7D, "llm", null,
                "第3段", null, "氛围描写稍长", "供用户参考", "质量原则", "第3段", false, false);

        fixture.service.complete(9L, List.of(warning));

        assertReportConclusion(fixture, "warning");
    }

    @Test
    void routesHighConfidenceFixableBlockerToNeedsRevision() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.batchReport();
        when(fixture.reportMapper.selectById(9L)).thenReturn(report);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.generation());
        EvaluationFinding blocker = new EvaluationFinding("causality-1", "causality", "blocking", 0.95D, "llm", null,
                "第5段", null, "人物行动缺少原因", "补足已有信息", "Brief 因果链", "第5段", true, true);

        fixture.service.complete(9L, List.of(blocker));

        assertReportConclusion(fixture, "needs_revision");
    }

    @Test
    void routesSourceConflictAndLowConfidenceMajorIssueToNeedsHuman() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.batchReport();
        when(fixture.reportMapper.selectById(9L)).thenReturn(report);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.generation());
        EvaluationFinding conflict = new EvaluationFinding("source-1", "source_conflict", "blocking", 0.65D, "llm", null,
                "第8段", null, "权威来源互相冲突", "人工决定来源", "Brief 与状态账", "全文", true, false);

        fixture.service.complete(9L, List.of(conflict));

        assertReportConclusion(fixture, "needs_human");
    }

    @Test
    void rejectsStoryFactOutsideFrozenSnapshot() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.report();
        report.setSourceSnapshotJson("{\"contextSnapshot\":\"{\\\"items\\\":[{\\\"sourceId\\\":\\\"allowed-1\\\"}]}\"}");
        when(fixture.reportMapper.selectById(9L)).thenReturn(report);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.generation());
        when(fixture.sceneMapper.selectById(7L)).thenReturn(fixture.scene());
        EvaluationFinding finding = new EvaluationFinding("fact", "causality", "warning", 0.9D, "llm", 7L,
                "第1段", "foreign-2", "越权事实", "人工处理");

        assertThatThrownBy(() -> fixture.service.validateSemanticFindings(9L, List.of(finding)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void marksReportStaleInsteadOfCompletingWhenFrozenSourceChanges() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.report();
        when(fixture.reportMapper.selectById(9L)).thenReturn(report);
        ChapterGenerationEntity changed = fixture.generation();
        changed.setGeneratedContent("已变更正文");
        when(fixture.generationMapper.selectById(3L)).thenReturn(changed);
        when(fixture.sceneMapper.selectById(7L)).thenReturn(fixture.scene());

        fixture.service.complete(9L, List.of());

        verify(fixture.reportMapper).update(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emitsBlockingFindingWhenScenePlanKeyOrSequenceDiffers() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.report();
        ChapterGenerationSceneEntity scene = fixture.scene();
        scene.setScenePlanVersionId(99L);
        scene.setSceneKey("generated-key");
        scene.setSequenceNo(2);
        ScenePlanVersionEntity plan = new ScenePlanVersionEntity();
        plan.setId(99L);
        plan.setSceneKey("planned-key");
        plan.setSequenceNo(1);
        plan.setContentJson("{\"sceneKey\":\"planned-key\",\"sequence\":1,\"title\":\"t\",\"participants\":[],\"requiredSettings\":[],\"foreshadowingActions\":[],\"status\":\"planned\",\"outlineBeatKeys\":[]}");
        plan.setDeleted(0);
        when(fixture.reportMapper.selectById(9L)).thenReturn(report);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.generation());
        when(fixture.sceneMapper.selectById(7L)).thenReturn(scene);
        when(fixture.planMapper.selectById(99L)).thenReturn(plan);

        org.assertj.core.api.Assertions.assertThat(fixture.service.deterministicFindings(9L))
                .anyMatch(item -> "scene-plan-order-mismatch".equals(item.issueKey()) && "blocking".equals(item.severity()));
    }

    @Test
    void rejectsSecondAutomaticRevisionWithoutInsert() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = fixture.report();
        report.setRevisionAttempt(1);
        when(fixture.reportMapper.selectById(9L)).thenReturn(report);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.generation());
        when(fixture.sceneMapper.selectById(7L)).thenReturn(fixture.scene());
        EvaluationFinding finding = new EvaluationFinding("style", "style", "warning", 0.9D, "llm", 7L,
                "第1段", null, "问题", "修订");

        assertThatThrownBy(() -> fixture.service.persistRevision(9L, List.of(finding), "修订正文"))
                .isInstanceOf(BusinessException.class);
        verify(fixture.revisionMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<com.dugnan.moqi.chapter.entity.ChapterGenerationRevisionCandidateEntity>any());
    }

    private void assertReportConclusion(Fixture fixture, String conclusion) {
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ChapterGenerationEvaluationReportEntity>>
                captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(fixture.reportMapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getParamNameValuePairs())
                .containsValue(conclusion);
    }

    private static final class Fixture {
        private final ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        private final ChapterGenerationSceneMapper sceneMapper = mock(ChapterGenerationSceneMapper.class);
        private final ChapterGenerationEvaluationReportMapper reportMapper = mock(ChapterGenerationEvaluationReportMapper.class);
        private final ChapterGenerationRevisionCandidateMapper revisionMapper = mock(ChapterGenerationRevisionCandidateMapper.class);
        private final ScenePlanVersionMapper planMapper = mock(ScenePlanVersionMapper.class);
        private final AiTaskMapper taskMapper = mock(AiTaskMapper.class);
        private final StoryContextSnapshotMapper contextSnapshotMapper = mock(StoryContextSnapshotMapper.class);
        private final ChapterAssetSourceSnapshotMapper assetSourceSnapshotMapper =
                mock(ChapterAssetSourceSnapshotMapper.class);
        private ChapterGenerationEntity currentGeneration;
        private final GenerationEvaluationServiceImpl service = new GenerationEvaluationServiceImpl(generationMapper, sceneMapper,
                reportMapper, revisionMapper, taskMapper, new ObjectMapper(),
                contextSnapshotMapper, assetSourceSnapshotMapper, planMapper);

        private ChapterGenerationEvaluationReportEntity createReport(
                String basisSnapshot,
                StoryContextSnapshotEntity contextSnapshot) {
            currentGeneration = generation();
            currentGeneration.setBasisSnapshotJson(basisSnapshot);
            currentGeneration.setSourceSnapshotId(21L);
            when(generationMapper.selectById(3L)).thenReturn(currentGeneration);
            when(assetSourceSnapshotMapper.selectById(21L)).thenReturn(assetSnapshot());
            when(contextSnapshotMapper.selectById(31L)).thenReturn(contextSnapshot);
            org.mockito.Mockito.doAnswer(invocation -> {
                com.dugnan.moqi.chapter.entity.AiTaskEntity task = invocation.getArgument(0);
                task.setId(8L);
                return 1;
            }).when(taskMapper).insert(org.mockito.ArgumentMatchers.any(
                    com.dugnan.moqi.chapter.entity.AiTaskEntity.class));
            AtomicReference<ChapterGenerationEvaluationReportEntity> inserted = new AtomicReference<>();
            org.mockito.Mockito.doAnswer(invocation -> {
                ChapterGenerationEvaluationReportEntity report = invocation.getArgument(0);
                report.setId(9L);
                inserted.set(report);
                return 1;
            }).when(reportMapper).insert(org.mockito.ArgumentMatchers.any(
                    ChapterGenerationEvaluationReportEntity.class));
            when(reportMapper.selectById(9L)).thenAnswer(invocation -> inserted.get());
            com.dugnan.moqi.agent.AgentRuntime runtime = mock(com.dugnan.moqi.agent.AgentRuntime.class);
            when(runtime.start(org.mockito.ArgumentMatchers.any())).thenReturn(
                    new com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView(
                            7L, GenerationEvaluationServiceImpl.WORKFLOW_TYPE, "queued", 1L, 12L, 8L,
                            "deterministic_check", null, null, null, null, null, null));
            service.setAgentRuntime(runtime);

            service.create(12L, 3L, new CreateEvaluationRequest(null, "quality-create"));
            return inserted.get();
        }

        private StoryContextSnapshotEntity context(String contentHash, String snapshotJson) {
            StoryContextSnapshotEntity context = new StoryContextSnapshotEntity();
            context.setId(31L);
            context.setContentHash(contentHash);
            context.setSnapshotJson(snapshotJson);
            context.setDeleted(0);
            return context;
        }

        private ChapterAssetSourceSnapshotEntity assetSnapshot() {
            ChapterAssetSourceSnapshotEntity snapshot = new ChapterAssetSourceSnapshotEntity();
            snapshot.setId(21L);
            snapshot.setWorkId(1L);
            snapshot.setChapterId(12L);
            snapshot.setAssetType("generation");
            snapshot.setAssetId(3L);
            snapshot.setAssetVersion(1);
            snapshot.setSourceConsensusVersionId(41L);
            snapshot.setSourceOutlineId(51L);
            snapshot.setSourceOutlineRevision(2);
            snapshot.setSourceScenePlanVersionId(61L);
            snapshot.setSourceContextSnapshotId(31L);
            snapshot.setSourceContentHash("asset-source-hash");
            snapshot.setDeleted(0);
            return snapshot;
        }

        private void assertMarkedStale() {
            org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ChapterGenerationEvaluationReportEntity>>
                    captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
            verify(reportMapper, org.mockito.Mockito.atLeastOnce())
                    .update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
            assertThat(captor.getAllValues()).anySatisfy(update ->
                    assertThat(update.getParamNameValuePairs()).containsValue("stale"));
        }

        private ChapterGenerationEvaluationReportEntity report() {
            ChapterGenerationEvaluationReportEntity report = new ChapterGenerationEvaluationReportEntity();
            report.setId(9L);
            report.setGenerationId(3L);
            report.setGenerationSceneId(7L);
            report.setReportStatus("running");
            report.setInputFingerprint(fingerprint("原正文", "原场景"));
            report.setVersion(0);
            report.setDeleted(0);
            return report;
        }

        private ChapterGenerationEvaluationReportEntity batchReport() {
            ChapterGenerationEvaluationReportEntity report = new ChapterGenerationEvaluationReportEntity();
            report.setId(9L);
            report.setGenerationId(3L);
            report.setReportStatus("running");
            report.setInputFingerprint(batchFingerprint("原正文"));
            report.setVersion(0);
            report.setDeleted(0);
            return report;
        }

        private ChapterGenerationEntity generation() {
            ChapterGenerationEntity generation = new ChapterGenerationEntity();
            generation.setId(3L);
            generation.setWorkId(1L);
            generation.setChapterId(12L);
            generation.setGenerationStatus("preview");
            generation.setBasisSnapshotJson("{}");
            generation.setGeneratedContent("原正文");
            generation.setVersion(1);
            generation.setDeleted(0);
            return generation;
        }

        private ChapterGenerationSceneEntity scene() {
            ChapterGenerationSceneEntity scene = new ChapterGenerationSceneEntity();
            scene.setId(7L);
            scene.setGenerationId(3L);
            scene.setGeneratedContent("原场景");
            scene.setContentHash("scene-hash");
            scene.setDeleted(0);
            return scene;
        }

        private String fingerprint(String generationContent, String sceneContent) {
            try {
                java.util.Map<String, Object> source = new java.util.LinkedHashMap<>();
                source.put("generationId", 3L);
                source.put("generationVersion", 1);
                source.put("generationContentHash", hash(generationContent));
                source.put("generationContent", generationContent);
                source.put("basisSnapshot", new ObjectMapper().readTree("{}"));
                source.put("sceneId", 7L);
                source.put("sceneContentHash", "scene-hash");
                source.put("sceneContent", sceneContent);
                source.put("assetSourceSnapshotId", null);
                source.put("assetSourceSnapshot", null);
                source.put("contextSnapshotId", null);
                source.put("contextSnapshotHash", null);
                source.put("contextSnapshot", null);
                return hash(GenerationEvaluationServiceImpl.RULESET_VERSION + "\n"
                        + GenerationEvaluationServiceImpl.EVALUATOR_VERSION + "\n"
                        + new ObjectMapper().writeValueAsString(source));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private String batchFingerprint(String generationContent) {
            try {
                java.util.Map<String, Object> source = new java.util.LinkedHashMap<>();
                source.put("generationId", 3L);
                source.put("generationVersion", 1);
                source.put("generationContentHash", hash(generationContent));
                source.put("generationContent", generationContent);
                source.put("basisSnapshot", new ObjectMapper().readTree("{}"));
                source.put("sceneId", null);
                source.put("sceneContentHash", null);
                source.put("sceneContent", null);
                source.put("assetSourceSnapshotId", null);
                source.put("assetSourceSnapshot", null);
                source.put("contextSnapshotId", null);
                source.put("contextSnapshotHash", null);
                source.put("contextSnapshot", null);
                return hash(GenerationEvaluationServiceImpl.RULESET_VERSION + "\n"
                        + GenerationEvaluationServiceImpl.EVALUATOR_VERSION + "\n"
                        + new ObjectMapper().writeValueAsString(source));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private String hash(String value) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
