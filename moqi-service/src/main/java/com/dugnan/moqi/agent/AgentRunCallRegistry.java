package com.dugnan.moqi.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.dugnan.moqi.llm.LlmStreamCall;

/**
 * 维护 Run 与活动模型调用的取消关联。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 维护 Agent Run 与活动模型调用的线程安全取消关联。
 */
@Component
public class AgentRunCallRegistry {

    private final ConcurrentHashMap<Long, LlmStreamCall> calls = new ConcurrentHashMap<>();
    private final Set<Long> cancellations = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Integer> activeExecutions = new ConcurrentHashMap<>();

    public synchronized void beginExecution(Long runId) {
        activeExecutions.merge(runId, 1, Integer::sum);
    }

    public synchronized void endExecution(Long runId) {
        Integer count = activeExecutions.get(runId);
        if (count == null || count <= 1) {
            activeExecutions.remove(runId);
            cancellations.remove(runId);
            return;
        }
        activeExecutions.put(runId, count - 1);
    }

    public synchronized void register(Long runId, LlmStreamCall call) {
        LlmStreamCall previous = calls.putIfAbsent(runId, call);
        if (previous != null) {
            call.cancel();
            throw new IllegalStateException("同一 Agent Run 已存在活动模型调用");
        }
        if (cancellations.contains(runId)) {
            call.cancel();
        }
    }

    public synchronized void unregister(Long runId, LlmStreamCall call) {
        calls.remove(runId, call);
    }

    public synchronized void cancel(Long runId) {
        cancel(runId, true);
    }

    public synchronized void cancel(Long runId, boolean retainRequest) {
        if (retainRequest) {
            cancellations.add(runId);
        } else {
            cancellations.remove(runId);
        }
        LlmStreamCall call = calls.get(runId);
        if (call != null) {
            call.cancel();
        }
    }

    public synchronized boolean isCancellationRequested(Long runId) {
        return cancellations.contains(runId);
    }
}
