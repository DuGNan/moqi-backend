package com.dugnan.moqi.agent;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证启动恢复与周期重派不会混淆正常运行中的 Agent Run。
 */
class AgentRunRecoveryRunnerTest {

    @Test
    void startupRecoversRunningRunsButPeriodicScanOnlyDispatchesQueuedRuns() throws Exception {
        AgentRuntimeService runtime = org.mockito.Mockito.mock(AgentRuntimeService.class);
        AgentRunRecoveryRunner runner = new AgentRunRecoveryRunner(runtime);

        runner.recover();

        verify(runtime).timeoutExpiredRuns();
        verify(runtime).dispatchQueuedRuns();
        verify(runtime, never()).recoverPendingRuns();

        runner.run(new DefaultApplicationArguments());

        verify(runtime).recoverPendingRuns();
    }
}
