package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.mapper.BoundedChapterRevisionMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 验证生成结果只物化一个稳定候选并建立候选谱系根节点。
 */
class ProseCandidateMaterializationServiceImplTest {

    @Test
    void materializesGenerationOnceAndSetsSelfAsRoot() {
        ChapterProseCandidateMapper candidateMapper = mock(ChapterProseCandidateMapper.class);
        BoundedChapterRevisionMapper boundedMapper = mock(BoundedChapterRevisionMapper.class);
        ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        ProseCandidateMaterializationServiceImpl service =
                new ProseCandidateMaterializationServiceImpl(candidateMapper, boundedMapper, generationMapper);
        ChapterGenerationEntity generation = generation();
        when(candidateMapper.insert(any(ChapterProseCandidateEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterProseCandidateEntity.class).setId(8L);
            return 1;
        });

        service.materialize(generation);

        ArgumentCaptor<ChapterProseCandidateEntity> candidate =
                ArgumentCaptor.forClass(ChapterProseCandidateEntity.class);
        verify(candidateMapper).insert(candidate.capture());
        assertThat(candidate.getValue().getSourceGenerationId()).isEqualTo(3L);
        assertThat(candidate.getValue().getQualityRequestStatus()).isEqualTo("pending");
        assertThat(candidate.getValue().getContentHash()).hasSize(64);
        verify(candidateMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void repeatedMaterializationKeepsExistingStableCandidate() {
        ChapterProseCandidateMapper candidateMapper = mock(ChapterProseCandidateMapper.class);
        BoundedChapterRevisionMapper boundedMapper = mock(BoundedChapterRevisionMapper.class);
        ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        ProseCandidateMaterializationServiceImpl service =
                new ProseCandidateMaterializationServiceImpl(candidateMapper, boundedMapper, generationMapper);
        ChapterProseCandidateEntity existing = new ChapterProseCandidateEntity();
        existing.setId(8L);
        when(candidateMapper.selectOne(any())).thenReturn(existing);

        service.materialize(generation());

        verify(candidateMapper, never()).insert(any(ChapterProseCandidateEntity.class));
        verify(candidateMapper).synchronizeGenerationStatuses(12L);
    }

    @Test
    void reloadsCommittedGenerationBeforeMaterialization() {
        ChapterProseCandidateMapper candidateMapper = mock(ChapterProseCandidateMapper.class);
        BoundedChapterRevisionMapper boundedMapper = mock(BoundedChapterRevisionMapper.class);
        ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        ProseCandidateMaterializationServiceImpl service =
                new ProseCandidateMaterializationServiceImpl(candidateMapper, boundedMapper, generationMapper);
        when(generationMapper.selectById(3L)).thenReturn(generation());

        service.materializeByGenerationId(3L);

        verify(generationMapper).selectById(3L);
        verify(candidateMapper).insert(any(ChapterProseCandidateEntity.class));
    }

    private static ChapterGenerationEntity generation() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(3L);
        generation.setWorkId(1L);
        generation.setChapterId(12L);
        generation.setGenerationStatus("preview");
        generation.setGeneratedContent("候选正文");
        generation.setDeleted(0);
        generation.setVersion(0);
        return generation;
    }
}
