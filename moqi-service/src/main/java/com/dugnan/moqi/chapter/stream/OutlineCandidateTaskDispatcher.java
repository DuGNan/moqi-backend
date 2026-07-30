package com.dugnan.moqi.chapter.stream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 在事务提交后把大纲调整候选任务交给既有有界执行器。
 */
@Component
public class OutlineCandidateTaskDispatcher {

    private final ThreadPoolTaskExecutor taskExecutor;
    private final OutlineCandidateTaskRunner taskRunner;

    /**
     * 创建候选任务调度器。
     */
    public OutlineCandidateTaskDispatcher(
            @Qualifier("chapterAiTaskExecutor") ThreadPoolTaskExecutor taskExecutor,
            OutlineCandidateTaskRunner taskRunner) {
        this.taskExecutor = taskExecutor;
        this.taskRunner = taskRunner;
    }

    /**
     * 提交后异步执行候选任务。
     *
     * @param event 已提交任务事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(OutlineCandidateTaskSubmittedEvent event) {
        try {
            taskExecutor.execute(() -> taskRunner.run(event.taskId()));
        } catch (RuntimeException exception) {
            taskRunner.reject(event.taskId());
        }
    }
}
