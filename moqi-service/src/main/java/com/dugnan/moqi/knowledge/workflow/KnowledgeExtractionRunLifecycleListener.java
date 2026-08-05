package com.dugnan.moqi.knowledge.workflow;

import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.event.AgentRunEvent;
import com.dugnan.moqi.knowledge.service.impl.KnowledgeExtractionServiceImpl;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 将知识提取 Agent Run 的取消、失败和超时终态同步到提取批次。
 */
@Component
public class KnowledgeExtractionRunLifecycleListener {

    private final KnowledgeExtractionServiceImpl extractionService;

    public KnowledgeExtractionRunLifecycleListener(
            KnowledgeExtractionServiceImpl extractionService) {
        this.extractionService = extractionService;
    }

    @EventListener
    public void handle(AgentRunEvent event) {
        if (!KnowledgeExtractionServiceImpl.WORKFLOW_TYPE.equals(event.workflowType())
                || !Set.of("failed", "canceled", "timed_out").contains(event.runStatus())) {
            return;
        }
        extractionService.markRunTerminal(event.runId(), event.runStatus());
    }
}
