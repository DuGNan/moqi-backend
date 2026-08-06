package com.dugnan.moqi.chapter.stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.dugnan.moqi.agent.event.AgentRunEvent;

class ChapterSseRegistryTest {

    @Test
    void removesDisconnectedEmitterWithoutCompletingItWithTheSendError() throws Exception {
        ChapterSseRegistry registry = new ChapterSseRegistry();
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("client disconnected"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        subscribers(registry).put(65L, new CopyOnWriteArrayList<>(java.util.List.of(emitter)));

        registry.forward(AgentRunEvent.updated(
                65L, 7L, "scene_plan_consistency_v1", 87L,
                "running", 1L, "rule_check", "running", 1L, null));

        verify(emitter, never()).completeWithError(any());
        org.assertj.core.api.Assertions.assertThat(subscribers(registry)).doesNotContainKey(65L);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> subscribers(
            ChapterSseRegistry registry) throws ReflectiveOperationException {
        Field field = ChapterSseRegistry.class.getDeclaredField("subscribers");
        field.setAccessible(true);
        return (ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>) field.get(registry);
    }
}
