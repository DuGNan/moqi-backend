package com.dugnan.moqi.chapter.workflow;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 在讨论回复提交后创建幂等的章节共识成熟度 Run。
 */
@Component
public class ChapterConsensusMaturityStarter {
    private static final String WORKFLOW_TYPE = "chapter_consensus_maturity_v1";
    private final AgentRuntime agentRuntime;

    public ChapterConsensusMaturityStarter(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    public void start(Long workId, Long chapterId, Long conversationId, Long userMessageId, Long assistantMessageId,
            Long baseBriefId, Long aiTaskId) {
        String baseBriefKey = baseBriefId == null ? "none" : baseBriefId.toString();
        String key = chapterId + ":" + conversationId + ":" + assistantMessageId + ":" + baseBriefKey + ":v1";
        agentRuntime.start(new StartAgentRunCommand("system", workId, chapterId, WORKFLOW_TYPE,
                key, assistantMessageId, Map.of("conversationId", conversationId, "chapterId", chapterId,
                "userMessageId", userMessageId, "assistantMessageId", assistantMessageId,
                "baseBriefId", baseBriefKey, "evaluatorVersion", "v1"), aiTaskId));
    }
}
