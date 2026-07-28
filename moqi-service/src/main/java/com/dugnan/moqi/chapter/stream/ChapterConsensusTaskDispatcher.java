package com.dugnan.moqi.chapter.stream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 在事务提交后把共识任务交给既有有界章节执行器。
 */
@Component
public class ChapterConsensusTaskDispatcher {

    private final ThreadPoolTaskExecutor taskExecutor;

    private final ChapterConsensusTaskRunner taskRunner;

    /**
     * 创建共识任务调度器。
     */
    public ChapterConsensusTaskDispatcher(
            @Qualifier("chapterAiTaskExecutor") ThreadPoolTaskExecutor taskExecutor,
            ChapterConsensusTaskRunner taskRunner) {
        this.taskExecutor = taskExecutor;
        this.taskRunner = taskRunner;
    }

    /**
     * 事务提交后调度任务。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(ChapterConsensusTaskSubmittedEvent event) {
        try {
            taskExecutor.execute(() -> taskRunner.run(event.taskId()));
        } catch (RuntimeException exception) {
            taskRunner.reject(event.taskId());
        }
    }
}
