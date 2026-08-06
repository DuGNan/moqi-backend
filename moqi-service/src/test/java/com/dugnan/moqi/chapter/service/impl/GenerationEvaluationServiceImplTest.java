package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationFinding;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationRevisionCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.context.mapper.StoryContextSnapshotMapper;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 验证正文评价的冻结来源越权拒绝和来源过期保护。
 */
class GenerationEvaluationServiceImplTest {

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

    private static final class Fixture {
        private final ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        private final ChapterGenerationSceneMapper sceneMapper = mock(ChapterGenerationSceneMapper.class);
        private final ChapterGenerationEvaluationReportMapper reportMapper = mock(ChapterGenerationEvaluationReportMapper.class);
        private final ChapterGenerationRevisionCandidateMapper revisionMapper = mock(ChapterGenerationRevisionCandidateMapper.class);
        private final ScenePlanVersionMapper planMapper = mock(ScenePlanVersionMapper.class);
        private final GenerationEvaluationServiceImpl service = new GenerationEvaluationServiceImpl(generationMapper, sceneMapper,
                reportMapper, revisionMapper, mock(AiTaskMapper.class), new ObjectMapper(),
                mock(StoryContextSnapshotMapper.class), planMapper);

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

        private ChapterGenerationEntity generation() {
            ChapterGenerationEntity generation = new ChapterGenerationEntity();
            generation.setId(3L);
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
                source.put("sceneId", 7L);
                source.put("sceneContentHash", "scene-hash");
                source.put("sceneContent", sceneContent);
                source.put("contextSnapshotId", null);
                source.put("contextSnapshotHash", null);
                source.put("contextSnapshot", null);
                return hash(new ObjectMapper().writeValueAsString(source));
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
