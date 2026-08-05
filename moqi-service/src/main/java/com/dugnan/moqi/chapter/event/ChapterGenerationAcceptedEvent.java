package com.dugnan.moqi.chapter.event;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 在正文采纳事务提交后通知知识提取入口，不携带正文内容。
 */
public record ChapterGenerationAcceptedEvent(Long chapterId, Long generationId) {
}
