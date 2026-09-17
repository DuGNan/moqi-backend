package com.dugnan.moqi.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;
import com.dugnan.moqi.knowledge.entity.ReleaseKnowledgeSnapshotEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.ReleaseKnowledgeSnapshotMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 验证知识快照的完整内容、历史封存和空值恢复。
 */
class ReleaseKnowledgeSnapshotsTest {
    private final ReleaseKnowledgeSnapshotMapper snapshots = mock(ReleaseKnowledgeSnapshotMapper.class);
    private final ChapterSummaryMapper summaries = mock(ChapterSummaryMapper.class);
    private final SettingEntryMapper settings = mock(SettingEntryMapper.class);
    private final ForeshadowingItemMapper foreshadowing = mock(ForeshadowingItemMapper.class);
    private final ChapterKeyEventMapper events = mock(ChapterKeyEventMapper.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ReleaseKnowledgeSnapshots service =
            new ReleaseKnowledgeSnapshots(snapshots, settings, foreshadowing, summaries, events, json);

    @BeforeEach
    void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                ChapterSummaryEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                ReleaseKnowledgeSnapshotEntity.class);
    }

    @Test
    void capturesCompleteContentAndAnExplicitEmptyCollection() {
        ChapterSummaryEntity summary = summary("旧摘要", 2);
        when(summaries.selectList(any())).thenReturn(List.of(summary));
        service.capture(1L, 10L, true);
        ArgumentCaptor<ReleaseKnowledgeSnapshotEntity> captured =
                ArgumentCaptor.forClass(ReleaseKnowledgeSnapshotEntity.class);
        verify(snapshots).insert(captured.capture());
        assertThat(captured.getValue().getSnapshotJson()).contains("旧摘要", "\"events\":[]");
        assertThat(captured.getValue().getSealed()).isEqualTo(1);
    }

    @Test
    void refusesToOverwriteHistoricalSnapshot() {
        ReleaseKnowledgeSnapshotEntity existing = snapshot("{}");
        existing.setSealed(1);
        when(snapshots.selectOne(any())).thenReturn(existing);
        assertThatThrownBy(() -> service.capture(1L, 10L, true)).isInstanceOf(BusinessException.class);
        verify(snapshots, never()).update(isNull(), any());
    }

    @Test
    void missingHistoricalSnapshotDoesNotEraseCurrentKnowledge() {
        assertThatThrownBy(() -> service.restore(1L, 9L)).isInstanceOf(BusinessException.class);
        verify(summaries, never()).update(isNull(), any());
        verify(settings, never()).update(isNull(), any());
    }

    @Test
    void restoresOldContentAndExplicitNullWithoutReusingHistoricalVersion() throws Exception {
        ChapterSummaryEntity historical = summary("旧摘要", 2);
        historical.setOpenQuestionsJson(null);
        ChapterSummaryEntity live = summary("新摘要", 7);
        live.setOpenQuestionsJson("[\"新问题\"]");
        String encoded = json.writeValueAsString(new ReleaseKnowledgeSnapshots.Snapshot(
                List.of(), List.of(), List.of(historical), List.of()));
        when(snapshots.selectOne(any())).thenReturn(snapshot(encoded));
        when(snapshots.allSummaries(1L)).thenReturn(List.of(live));
        when(snapshots.restoreProjection(any(), any(), any())).thenReturn(1);
        service.restore(1L, 9L);
        ArgumentCaptor<ChapterSummaryEntity> restored = ArgumentCaptor.forClass(ChapterSummaryEntity.class);
        verify(snapshots).restoreProjection(restored.capture(), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(7));
        assertThat(restored.getValue().getSummary()).isEqualTo("旧摘要");
        assertThat(restored.getValue().getOpenQuestionsJson()).isNull();
        String sql = new ReleaseKnowledgeSnapshotMapper.ProjectionSql().restore(
                java.util.Map.of("row", restored.getValue()));
        assertThat(sql).contains("open_questions_json=#{row.openQuestionsJson}", "version=#{expectedVersion}+1");
    }

    @Test
    void concurrentProjectionChangeAbortsRestore() throws Exception {
        String encoded = json.writeValueAsString(new ReleaseKnowledgeSnapshots.Snapshot(
                List.of(), List.of(), List.of(summary("旧摘要", 2)), List.of()));
        when(snapshots.selectOne(any())).thenReturn(snapshot(encoded));
        when(snapshots.allSummaries(1L)).thenReturn(List.of(summary("新摘要", 7)));
        when(snapshots.restoreProjection(any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.restore(1L, 9L)).isInstanceOf(BusinessException.class);
    }

    private ChapterSummaryEntity summary(String content, int version) {
        ChapterSummaryEntity summary = new ChapterSummaryEntity();
        summary.setId(3L);
        summary.setWorkId(1L);
        summary.setChapterId(2L);
        summary.setSummary(content);
        summary.setVersion(version);
        summary.setDeleted(0);
        return summary;
    }

    private ReleaseKnowledgeSnapshotEntity snapshot(String content) {
        ReleaseKnowledgeSnapshotEntity snapshot = new ReleaseKnowledgeSnapshotEntity();
        snapshot.setId(1L);
        snapshot.setWorkId(1L);
        snapshot.setReleaseId(9L);
        snapshot.setSnapshotJson(content);
        snapshot.setVersion(0);
        snapshot.setSealed(1);
        return snapshot;
    }
}
