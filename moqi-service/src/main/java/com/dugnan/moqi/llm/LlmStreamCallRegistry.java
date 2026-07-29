package com.dugnan.moqi.llm;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dugnan.moqi.task.event.AiTaskCancellationSignal;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 按任务登记活动模型调用并处理先取消后注册等并发顺序。
 */
@Component
public class LlmStreamCallRegistry {

    private final ConcurrentHashMap<Long, LlmStreamCall> calls = new ConcurrentHashMap<>();
    private final Set<Long> cancellations = ConcurrentHashMap.newKeySet();

    /**
     * 登记任务的唯一活动模型调用，并处理先取消后注册。
     *
     * @param taskId 任务 ID
     * @param call 活动模型调用
     */
    public void register(Long taskId, LlmStreamCall call) {
        LlmStreamCall existing = calls.putIfAbsent(taskId, call);
        if (existing != null) {
            call.cancel();
            throw new IllegalStateException("AI 任务已存在活动模型调用");
        }
        if (cancellations.contains(taskId)) {
            call.cancel();
        }
    }

    /**
     * 按调用实例移除活动登记与取消信号。
     *
     * @param taskId 任务 ID
     * @param call 已结束模型调用
     */
    public void unregister(Long taskId, LlmStreamCall call) {
        if (call != null) {
            calls.remove(taskId, call);
        }
        cancellations.remove(taskId);
    }

    /**
     * 在任务取消事务提交后转发模型调用取消。
     *
     * @param signal AI 任务取消信号
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onCancellation(AiTaskCancellationSignal signal) {
        cancel(signal.taskId());
    }

    /**
     * 记录取消意图并取消当前活动调用。
     *
     * @param taskId 任务 ID
     */
    public void cancel(Long taskId) {
        cancellations.add(taskId);
        LlmStreamCall call = calls.get(taskId);
        if (call != null) {
            call.cancel();
        }
    }

    /**
     * 查询任务是否已收到取消请求。
     *
     * @param taskId 任务 ID
     * @return 是否已请求取消
     */
    public boolean isCancellationRequested(Long taskId) {
        return cancellations.contains(taskId);
    }

    int activeCallCount() {
        return calls.size();
    }

    int cancellationCount() {
        return cancellations.size();
    }
}
