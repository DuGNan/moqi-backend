package com.dugnan.moqi.chapter.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.ProseCandidateMaterializationService;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证完整正文完成后自动进入独立质量评价。
 */
class ChapterGenerationCompletionHandlerTest {

    @Test
    void startsMandatoryWholeChapterEvaluation() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);
        ProseCandidateMaterializationService materializationService =
                mock(ProseCandidateMaterializationService.class);
        ChapterGenerationCompletionHandler handler =
                new ChapterGenerationCompletionHandler(publisher, evaluationService, materializationService);
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(3L);
        generation.setChapterId(12L);

        handler.generationCompleted(generation);

        verify(evaluationService).createAutomatic(12L, 3L);
        verify(materializationService).materialize(generation);
        verify(materializationService).markQualityRequested(3L);
        org.mockito.ArgumentCaptor<Object> eventCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue())
                .isInstanceOfSatisfying(com.dugnan.moqi.chapter.stream.SceneGenerationEvent.class,
                        event -> org.assertj.core.api.Assertions.assertThat(event.generationStatus())
                                .isEqualTo("preview"));
    }

    @Test
    void keepsMaterializedCandidateWhenEvaluationIsUnavailable() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);
        ProseCandidateMaterializationService materializationService =
                mock(ProseCandidateMaterializationService.class);
        ChapterGenerationCompletionHandler handler =
                new ChapterGenerationCompletionHandler(publisher, evaluationService, materializationService);
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(3L);
        generation.setChapterId(12L);
        doThrow(new IllegalStateException("provider unavailable"))
                .when(evaluationService).createAutomatic(12L, 3L);

        handler.generationCompleted(generation);

        verify(materializationService).materialize(generation);
        verify(materializationService).markQualityUnavailable(3L);
    }
}
