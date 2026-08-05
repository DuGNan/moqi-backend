package com.dugnan.moqi.knowledge.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dugnan.moqi.chapter.event.ChapterGenerationAcceptedEvent;
import com.dugnan.moqi.knowledge.service.KnowledgeExtractionService;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 在正文采纳事务提交后幂等创建知识提取批次，失败不回滚正文采纳。
 */
@Component
public class KnowledgeExtractionAcceptedListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(KnowledgeExtractionAcceptedListener.class);
    private final KnowledgeExtractionService extractionService;

    public KnowledgeExtractionAcceptedListener(KnowledgeExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void handle(ChapterGenerationAcceptedEvent event) {
        try {
            extractionService.startAcceptedGeneration(event.generationId());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "已采纳正文的知识提取入队失败，chapterId={}, generationId={}, errorType={}",
                    event.chapterId(),
                    event.generationId(),
                    exception.getClass().getSimpleName());
        }
    }
}
