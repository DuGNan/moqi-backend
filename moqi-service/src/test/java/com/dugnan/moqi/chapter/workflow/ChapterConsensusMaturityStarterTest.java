package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 验证回复完成事件使用稳定输入创建幂等成熟度 Run。
 */
@ExtendWith(MockitoExtension.class)
class ChapterConsensusMaturityStarterTest {
    @Mock private AgentRuntime agentRuntime;

    @Test
    void startsRunWithStableReplyIdempotencyKey() {
        ChapterConsensusMaturityStarter starter = new ChapterConsensusMaturityStarter(agentRuntime);

        starter.start(1L, 2L, 8L, 11L, 12L, 21L, 31L);
        starter.start(1L, 2L, 8L, 11L, 12L, 21L, 31L);

        ArgumentCaptor<StartAgentRunCommand> captor = ArgumentCaptor.forClass(StartAgentRunCommand.class);
        verify(agentRuntime, org.mockito.Mockito.times(2)).start(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(command -> {
            assertThat(command.workflowType()).isEqualTo("chapter_consensus_maturity_v1");
            assertThat(command.idempotencyKey()).isEqualTo("2:8:12:21:v1");
            assertThat(command.input()).containsEntry("assistantMessageId", 12L);
            assertThat(command.aiTaskId()).isEqualTo(31L);
        });
    }
}
