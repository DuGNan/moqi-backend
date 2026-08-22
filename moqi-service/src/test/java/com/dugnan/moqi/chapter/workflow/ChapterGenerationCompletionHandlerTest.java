package com.dugnan.moqi.chapter.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.event.ChapterGenerationCompletedEvent;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证完整正文完成后发布提交后候选处理事件。
 */
class ChapterGenerationCompletionHandlerTest {

    @Test
    void publishesCommittedGenerationEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChapterGenerationCompletionHandler handler = new ChapterGenerationCompletionHandler(publisher);
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(3L);
        generation.setChapterId(12L);

        handler.generationCompleted(generation);

        org.mockito.ArgumentCaptor<Object> eventCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getAllValues())
                .anySatisfy(event -> org.assertj.core.api.Assertions.assertThat(event)
                        .isInstanceOfSatisfying(com.dugnan.moqi.chapter.stream.SceneGenerationEvent.class,
                                item -> org.assertj.core.api.Assertions.assertThat(item.generationStatus())
                                        .isEqualTo("preview")))
                .anySatisfy(event -> org.assertj.core.api.Assertions.assertThat(event)
                        .isEqualTo(new ChapterGenerationCompletedEvent(12L, 3L)));
    }
}
