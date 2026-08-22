package com.dugnan.moqi.chapter.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dugnan.moqi.chapter.event.ChapterGenerationCompletedEvent;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.ProseCandidateMaterializationService;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 在生成事务提交后物化正文候选并启动整章质量评价。
 */
@Component
public class ProseCandidateGenerationCompletedListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProseCandidateGenerationCompletedListener.class);

    private final ProseCandidateMaterializationService materializationService;
    private final GenerationEvaluationService evaluationService;

    public ProseCandidateGenerationCompletedListener(
            ProseCandidateMaterializationService materializationService,
            GenerationEvaluationService evaluationService) {
        this.materializationService = materializationService;
        this.evaluationService = evaluationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(ChapterGenerationCompletedEvent event) {
        try {
            materializationService.materializeByGenerationId(event.generationId());
        } catch (RuntimeException exception) {
            LOGGER.error("正文候选提交后物化失败，chapterId={}，generationId={}",
                    event.chapterId(), event.generationId(), exception);
            return;
        }
        try {
            evaluationService.createAutomatic(event.chapterId(), event.generationId());
            materializationService.markQualityRequested(event.generationId());
        } catch (RuntimeException exception) {
            markQualityUnavailable(event, exception);
        }
    }

    private void markQualityUnavailable(ChapterGenerationCompletedEvent event, RuntimeException evaluationFailure) {
        try {
            materializationService.markQualityUnavailable(event.generationId());
        } catch (RuntimeException statusFailure) {
            evaluationFailure.addSuppressed(statusFailure);
        }
        LOGGER.error("整章候选自动评价启动失败，chapterId={}，generationId={}",
                event.chapterId(), event.generationId(), evaluationFailure);
    }
}
