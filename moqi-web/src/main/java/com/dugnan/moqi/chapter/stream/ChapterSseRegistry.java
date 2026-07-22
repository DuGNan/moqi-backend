package com.dugnan.moqi.chapter.stream;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 维护本实例的章节 SSE 订阅，并转发章节讨论回复事件。
 */
@Component
public class ChapterSseRegistry {

    private static final long NO_TIMEOUT = 0L;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long chapterId) {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        List<SseEmitter> emitters = subscribers.computeIfAbsent(chapterId, ignored -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> remove(chapterId, emitter));
        emitter.onTimeout(() -> remove(chapterId, emitter));
        send(emitter, "stream.ready", Map.of("chapterId", chapterId));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void forward(ChapterReplyEvent event) {
        List<SseEmitter> emitters = subscribers.get(event.chapterId());
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, event.type(), event);
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException exception) {
            removeEmitter(emitter);
            emitter.completeWithError(exception);
        }
    }

    private void remove(Long chapterId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(chapterId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            subscribers.remove(chapterId, emitters);
        }
    }

    private void removeEmitter(SseEmitter emitter) {
        subscribers.forEach((chapterId, emitters) -> remove(chapterId, emitter));
    }
}
