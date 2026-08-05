package com.dugnan.moqi.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.Evidence;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ExtractedCandidate;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ExtractionOutput;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeExtractionBatchEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeExtractionBatchMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * 验证已采纳正文知识提取的来源冻结与结构化输出边界。
 */
class KnowledgeExtractionServiceImplTest {

    private StoryKnowledgeExtractionBatchMapper batchMapper;
    private ChapterGenerationMapper generationMapper;
    private ChapterMapper chapterMapper;
    private SettingEntryMapper settingMapper;
    private KnowledgeExtractionStaleMarker staleMarker;
    private KnowledgeExtractionServiceImpl service;

    @BeforeEach
    void setUp() {
        batchMapper = mock(StoryKnowledgeExtractionBatchMapper.class);
        generationMapper = mock(ChapterGenerationMapper.class);
        chapterMapper = mock(ChapterMapper.class);
        settingMapper = mock(SettingEntryMapper.class);
        staleMarker = mock(KnowledgeExtractionStaleMarker.class);
        service = new KnowledgeExtractionServiceImpl(
                batchMapper,
                mock(StoryKnowledgeCandidateMapper.class),
                generationMapper,
                chapterMapper,
                mock(AiTaskMapper.class),
                settingMapper,
                mock(ForeshadowingItemMapper.class),
                mock(ChapterSummaryMapper.class),
                mock(ChapterKeyEventMapper.class),
                mock(AgentRuntime.class),
                new ObjectMapper(),
                staleMarker);
    }

    @Test
    void acceptsStructuredOutputBoundToExactAcceptedContent() {
        stubCurrentSource("夜雨停了。", 3);
        ExtractionOutput output = new ExtractionOutput(1, List.of(
                new ExtractedCandidate(
                        "summary-1",
                        "chapter_summary",
                        Map.of("summary", "夜雨停了。"),
                        new Evidence(0, 5, "夜雨停了。"))));

        ExtractionOutput validated = service.validateOutput(9L, output);

        assertThat(validated.schemaVersion()).isEqualTo(1);
        assertThat(validated.candidates()).hasSize(1);
    }

    @Test
    void rejectsEvidenceThatDoesNotMatchAcceptedContent() {
        stubCurrentSource("夜雨停了。", 3);
        ExtractionOutput output = new ExtractionOutput(1, List.of(
                new ExtractedCandidate(
                        "summary-1",
                        "chapter_summary",
                        Map.of("summary", "夜雨停了。"),
                        new Evidence(0, 2, "错误"))));

        assertThatThrownBy(() -> service.validateOutput(9L, output))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_INVALID);
    }

    @Test
    void rejectsCrossWorkSettingReference() {
        stubCurrentSource("他们抵达钟楼。", 3);
        SettingEntryEntity foreign = new SettingEntryEntity();
        foreign.setId(88L);
        foreign.setWorkId(2L);
        foreign.setEntryStatus("active");
        foreign.setDeleted(0);
        when(settingMapper.selectById(88L)).thenReturn(foreign);
        ExtractionOutput output = new ExtractionOutput(1, List.of(
                new ExtractedCandidate(
                        "summary-1",
                        "chapter_summary",
                        Map.of("summary", "他们抵达钟楼。"),
                        new Evidence(0, 7, "他们抵达钟楼。")),
                new ExtractedCandidate(
                        "event-1",
                        "key_event",
                        Map.of(
                                "title", "抵达",
                                "content", "他们抵达钟楼。",
                                "eventType", "plot",
                                "occurredOrder", 1,
                                "relatedSettingIds", List.of(88L),
                                "relatedForeshadowingIds", List.of()),
                        new Evidence(0, 7, "他们抵达钟楼。"))));

        assertThatThrownBy(() -> service.validateOutput(9L, output))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_INVALID);
    }

    @Test
    void marksBatchStaleWhenAcceptedContentChanges() {
        StoryKnowledgeExtractionBatchEntity batch = batch("原文", 3);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ChapterGenerationEntity generation = acceptedGeneration();
        when(generationMapper.selectById(7L)).thenReturn(generation);
        ChapterEntity chapter = chapter("改写后的正文", 4);
        when(chapterMapper.selectById(5L)).thenReturn(chapter);

        assertThatThrownBy(() -> service.sourceContent(9L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_STALE);
        verify(staleMarker).mark(9L);
    }

    @Test
    void rejectsGenerationThatHasNotBeenAccepted() {
        ChapterGenerationEntity generation = acceptedGeneration();
        generation.setGenerationStatus("succeeded");
        when(generationMapper.selectById(7L)).thenReturn(generation);

        assertThatThrownBy(() -> service.startAcceptedGeneration(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GENERATION_STATUS_CONFLICT);
    }

    private void stubCurrentSource(String content, int revision) {
        StoryKnowledgeExtractionBatchEntity batch = batch(content, revision);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(generationMapper.selectById(7L)).thenReturn(acceptedGeneration());
        when(chapterMapper.selectById(5L)).thenReturn(chapter(content, revision));
    }

    private StoryKnowledgeExtractionBatchEntity batch(String content, int revision) {
        StoryKnowledgeExtractionBatchEntity batch = new StoryKnowledgeExtractionBatchEntity();
        batch.setId(9L);
        batch.setWorkId(1L);
        batch.setChapterId(5L);
        batch.setGenerationId(7L);
        batch.setSourceContent(content);
        batch.setSourceContentRevision(revision);
        batch.setSourceFingerprint(sourceFingerprint(7L, revision, content));
        batch.setBatchStatus("running");
        batch.setDeleted(0);
        batch.setVersion(0);
        return batch;
    }

    private ChapterGenerationEntity acceptedGeneration() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(7L);
        generation.setWorkId(1L);
        generation.setChapterId(5L);
        generation.setGenerationStatus("accepted");
        generation.setDeleted(0);
        return generation;
    }

    private ChapterEntity chapter(String content, int revision) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(5L);
        chapter.setWorkId(1L);
        chapter.setContent(content);
        chapter.setVersion(revision);
        chapter.setDeleted(0);
        return chapter;
    }

    private String sourceFingerprint(Long generationId, int revision, String content) {
        try {
            var method = KnowledgeExtractionServiceImpl.class.getDeclaredMethod(
                    "fingerprint", Long.class, Integer.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, generationId, revision, content);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
