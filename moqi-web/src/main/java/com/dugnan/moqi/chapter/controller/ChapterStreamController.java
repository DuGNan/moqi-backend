package com.dugnan.moqi.chapter.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.dugnan.moqi.chapter.stream.ChapterSseRegistry;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 提供章节讨论任务的服务端事件订阅接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class ChapterStreamController {

    private final ChapterSseRegistry chapterSseRegistry;

    public ChapterStreamController(ChapterSseRegistry chapterSseRegistry) {
        this.chapterSseRegistry = chapterSseRegistry;
    }

    @GetMapping(value = "/{chapterId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long chapterId) {
        return chapterSseRegistry.subscribe(chapterId);
    }
}
