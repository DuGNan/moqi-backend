package com.dugnan.moqi.chapter.workflow;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.dugnan.moqi.chapter.event.ChapterGenerationCompletedEvent;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.ProseCandidateMaterializationService;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 验证生成事务提交后才物化稳定候选并启动整章评价。
 */
class ProseCandidateGenerationCompletedListenerTest {

    @Test
    void materializesCommittedGenerationBeforeStartingEvaluation() {
        ProseCandidateMaterializationService materializationService =
                mock(ProseCandidateMaterializationService.class);
        GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);
        ProseCandidateGenerationCompletedListener listener =
                new ProseCandidateGenerationCompletedListener(materializationService, evaluationService);

        listener.handle(new ChapterGenerationCompletedEvent(12L, 3L));

        InOrder order = inOrder(materializationService, evaluationService);
        order.verify(materializationService).materializeByGenerationId(3L);
        order.verify(evaluationService).createAutomatic(12L, 3L);
        order.verify(materializationService).markQualityRequested(3L);
    }

    @Test
    void keepsCandidateVisibleWhenEvaluationCannotStart() {
        ProseCandidateMaterializationService materializationService =
                mock(ProseCandidateMaterializationService.class);
        GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);
        doThrow(new IllegalStateException("provider unavailable"))
                .when(evaluationService).createAutomatic(12L, 3L);
        ProseCandidateGenerationCompletedListener listener =
                new ProseCandidateGenerationCompletedListener(materializationService, evaluationService);

        listener.handle(new ChapterGenerationCompletedEvent(12L, 3L));

        verify(materializationService).markQualityUnavailable(3L);
    }

    @Test
    void doesNotStartEvaluationWhenMaterializationFails() {
        ProseCandidateMaterializationService materializationService =
                mock(ProseCandidateMaterializationService.class);
        GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(materializationService).materializeByGenerationId(3L);
        ProseCandidateGenerationCompletedListener listener =
                new ProseCandidateGenerationCompletedListener(materializationService, evaluationService);

        listener.handle(new ChapterGenerationCompletedEvent(12L, 3L));

        verify(evaluationService, never()).createAutomatic(12L, 3L);
    }
}
