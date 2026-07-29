package com.dugnan.moqi.chapter.stream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 在创建任务的事务提交后将任务交给有界执行器。
 */
@Component
public class ConversationReplyTaskDispatcher {

    private final ThreadPoolTaskExecutor taskExecutor;
    private final ConversationReplyTaskRunner taskRunner;

    /**
     * 创建讨论回复任务调度器。
     *
     * @param taskExecutor 有界章节任务执行器
     * @param taskRunner 讨论回复任务执行器
     */
    public ConversationReplyTaskDispatcher(
            @Qualifier("chapterAiTaskExecutor") ThreadPoolTaskExecutor taskExecutor,
            ConversationReplyTaskRunner taskRunner) {
        this.taskExecutor = taskExecutor;
        this.taskRunner = taskRunner;
    }

    /**
     * 事务提交后调度讨论回复任务。
     *
     * @param event 讨论回复任务提交事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(ConversationReplyTaskSubmittedEvent event) {
        try {
            taskExecutor.execute(() -> taskRunner.run(event.taskId()));
        } catch (RuntimeException exception) {
            taskRunner.reject(event.taskId());
        }
    }
}
