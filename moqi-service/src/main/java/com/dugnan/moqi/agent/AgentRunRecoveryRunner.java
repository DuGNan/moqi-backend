package com.dugnan.moqi.agent;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 启动及周期性扫描可恢复和超时 Run。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 在应用启动和定时周期中扫描待恢复及超时 Agent Run。
 */
@Component
public class AgentRunRecoveryRunner implements ApplicationRunner {

    private final AgentRuntimeService runtime;

    public AgentRunRecoveryRunner(AgentRuntimeService runtime) {
        this.runtime = runtime;
    }

    @Override
    public void run(ApplicationArguments args) {
        runtime.timeoutExpiredRuns();
        runtime.recoverPendingRuns();
    }

    @Scheduled(fixedDelayString = "${moqi.agent-runtime.recovery-delay-ms:30000}")
    public void recover() {
        runtime.timeoutExpiredRuns();
        runtime.dispatchQueuedRuns();
    }
}
