package com.dugnan.moqi.chapter.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import com.dugnan.moqi.chapter.event.ChapterGenerationCompletedEvent;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.ProseCandidateMaterializationService;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 验证正文候选完成事件只在生成事务提交后触发后续处理。
 */
class ProseCandidateGenerationTransactionTest {

    @Test
    void ignoresCompletionEventWhenOuterTransactionRollsBack() {
        ProseCandidateMaterializationService materializationService =
                mock(ProseCandidateMaterializationService.class);
        GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);

        try (AnnotationConfigApplicationContext context = context(materializationService, evaluationService)) {
            beginTransactionSynchronization();
            try {
                context.publishEvent(new ChapterGenerationCompletedEvent(12L, 3L));
                TransactionSynchronizationUtils.triggerAfterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK);
            } finally {
                clearTransactionSynchronization();
            }

            verifyNoInteractions(materializationService, evaluationService);
        }
    }

    @Test
    void handlesCompletionEventAfterOuterTransactionCommits() {
        ProseCandidateMaterializationService materializationService =
                mock(ProseCandidateMaterializationService.class);
        GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);

        try (AnnotationConfigApplicationContext context = context(materializationService, evaluationService)) {
            beginTransactionSynchronization();
            try {
                context.publishEvent(new ChapterGenerationCompletedEvent(12L, 3L));
                TransactionSynchronizationUtils.triggerBeforeCommit(false);
                TransactionSynchronizationUtils.triggerBeforeCompletion();
                TransactionSynchronizationUtils.triggerAfterCommit();
                TransactionSynchronizationUtils.triggerAfterCompletion(
                        TransactionSynchronization.STATUS_COMMITTED);
            } finally {
                clearTransactionSynchronization();
            }

            verify(materializationService).materializeByGenerationId(3L);
            verify(evaluationService).createAutomatic(12L, 3L);
            verify(materializationService).markQualityRequested(3L);
        }
    }

    private AnnotationConfigApplicationContext context(
            ProseCandidateMaterializationService materializationService,
            GenerationEvaluationService evaluationService) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ProseCandidateMaterializationService.class, () -> materializationService);
        context.registerBean(GenerationEvaluationService.class, () -> evaluationService);
        context.registerBean(TransactionalEventListenerFactory.class);
        context.registerBean(ProseCandidateGenerationCompletedListener.class);
        context.refresh();
        return context;
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
