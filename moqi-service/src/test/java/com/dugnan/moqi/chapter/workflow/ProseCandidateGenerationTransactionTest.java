package com.dugnan.moqi.chapter.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

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
            TransactionTemplate transactionTemplate = new TransactionTemplate(new TestTransactionManager());

            transactionTemplate.executeWithoutResult(status -> {
                context.publishEvent(new ChapterGenerationCompletedEvent(12L, 3L));
                status.setRollbackOnly();
            });

            verifyNoInteractions(materializationService, evaluationService);
        }
    }

    @Test
    void handlesCompletionEventAfterOuterTransactionCommits() {
        ProseCandidateMaterializationService materializationService =
                mock(ProseCandidateMaterializationService.class);
        GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);

        try (AnnotationConfigApplicationContext context = context(materializationService, evaluationService)) {
            TransactionTemplate transactionTemplate = new TransactionTemplate(new TestTransactionManager());

            transactionTemplate.executeWithoutResult(status ->
                    context.publishEvent(new ChapterGenerationCompletedEvent(12L, 3L)));

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
        context.registerBean(ProseCandidateGenerationCompletedListener.class);
        context.refresh();
        return context;
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No resource is required; this manager only drives transaction synchronization callbacks.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // No resource is required; commit triggers the registered AFTER_COMMIT listener.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // No resource is required; rollback must discard the registered listener callback.
        }
    }
}
