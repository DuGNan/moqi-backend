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

    public void unregister(Long taskId, LlmStreamCall call) {
        if (call != null) {
            calls.remove(taskId, call);
        }
        cancellations.remove(taskId);
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onCancellation(AiTaskCancellationSignal signal) {
        cancel(signal.taskId());
    }

    public void cancel(Long taskId) {
        cancellations.add(taskId);
        LlmStreamCall call = calls.get(taskId);
        if (call != null) {
            call.cancel();
        }
    }

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
