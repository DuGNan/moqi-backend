package com.dugnan.moqi.chapter.workflow;

import java.time.LocalDateTime;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.event.AgentRunEvent;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.stream.SceneGenerationEvent;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 将场景生成 Agent Run 的失败和取消终态同步为生成批次状态。
 */
@Component
public class SceneGenerationRunLifecycleListener {

    private static final String WORKFLOW_TYPE = SceneNovelGenerationWorkflowDefinition.WORKFLOW_TYPE;

    private final ChapterGenerationMapper generationMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public SceneGenerationRunLifecycleListener(
            ChapterGenerationMapper generationMapper,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.generationMapper = generationMapper;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void handle(AgentRunEvent event) {
        if (!WORKFLOW_TYPE.equals(event.workflowType())
                || !Set.of("failed", "canceled", "timed_out").contains(event.runStatus())) {
            return;
        }
        String generationStatus = "canceled".equals(event.runStatus()) ? "canceled" : "failed";
        int changed = generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("agent_run_id", event.runId()).eq("deleted", 0)
                .in("generation_status", Set.of("queued", "running"))
                .set("generation_status", generationStatus).setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now()));
        if (changed > 0) {
            eventPublisher.publishEvent(SceneGenerationEvent.generation(
                    "generation." + generationStatus, event.chapterId(), generationId(event.runId()), generationStatus));
        }
    }

    private Long generationId(Long runId) {
        ChapterGenerationEntity generation = generationMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChapterGenerationEntity>()
                .eq(ChapterGenerationEntity::getAgentRunId, runId)
                .eq(ChapterGenerationEntity::getDeleted, 0));
        return generation == null ? null : generation.getId();
    }
}
