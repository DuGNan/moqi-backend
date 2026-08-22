package com.dugnan.moqi.chapter.event;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 标记完整正文生成事务已完成，供提交后候选物化处理。
 */
public record ChapterGenerationCompletedEvent(Long chapterId, Long generationId) {
}
