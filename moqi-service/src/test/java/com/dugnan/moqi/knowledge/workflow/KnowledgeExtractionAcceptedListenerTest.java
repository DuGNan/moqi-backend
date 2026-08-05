package com.dugnan.moqi.knowledge.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.event.ChapterGenerationAcceptedEvent;
import com.dugnan.moqi.knowledge.service.KnowledgeExtractionService;

/**
 * 验证提取失败不会反向破坏正文采纳事务。
 */
class KnowledgeExtractionAcceptedListenerTest {

    @Test
    void startsExtractionAfterGenerationAcceptance() {
        KnowledgeExtractionService service = mock(KnowledgeExtractionService.class);
        KnowledgeExtractionAcceptedListener listener = new KnowledgeExtractionAcceptedListener(service);

        listener.handle(new ChapterGenerationAcceptedEvent(5L, 7L));

        verify(service).startAcceptedGeneration(7L);
    }

    @Test
    void swallowsExtractionFailureAfterAcceptance() {
        KnowledgeExtractionService service = mock(KnowledgeExtractionService.class);
        when(service.startAcceptedGeneration(7L)).thenThrow(new IllegalStateException("provider unavailable"));
        KnowledgeExtractionAcceptedListener listener = new KnowledgeExtractionAcceptedListener(service);

        listener.handle(new ChapterGenerationAcceptedEvent(5L, 7L));

        verify(service).startAcceptedGeneration(7L);
    }
}
