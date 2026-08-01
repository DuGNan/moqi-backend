package com.dugnan.moqi.chapter.workflow;

import java.time.LocalDateTime;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.agent.event.AgentRunEvent;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
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
    private final ChapterGenerationSceneMapper sceneMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public SceneGenerationRunLifecycleListener(
            ChapterGenerationMapper generationMapper,
            ChapterGenerationSceneMapper sceneMapper,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.generationMapper = generationMapper;
        this.sceneMapper = sceneMapper;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    @Transactional(rollbackFor = RuntimeException.class)
    public void handle(AgentRunEvent event) {
        if (!WORKFLOW_TYPE.equals(event.workflowType())
                || !Set.of("failed", "canceled", "timed_out").contains(event.runStatus())) {
            return;
        }
        String generationStatus = "canceled".equals(event.runStatus()) ? "canceled" : "failed";
        ChapterGenerationEntity generation = generationForRun(event.runId());
        int sceneChanged = updateActiveScene(generation, event.stepKey(), generationStatus);
        int changed = generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("agent_run_id", event.runId()).eq("deleted", 0)
                .in("generation_status", Set.of("queued", "running"))
                .set("generation_status", generationStatus).setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now()));
        if (changed > 0) {
            eventPublisher.publishEvent(SceneGenerationEvent.generation(
                    "generation." + generationStatus, event.chapterId(), generationId(event.runId()), generationStatus));
        }
        if (sceneChanged > 0 && generation != null) {
            String sceneKey = event.stepKey().substring("generate_scene:".length());
            eventPublisher.publishEvent(SceneGenerationEvent.scene("generation.scene." + generationStatus,
                    generation.getChapterId(), generation.getId(), null, sceneKey, generationStatus));
        }
    }

    private int updateActiveScene(ChapterGenerationEntity generation, String stepKey, String generationStatus) {
        if (generation == null || stepKey == null || !stepKey.startsWith("generate_scene:")) {
            return 0;
        }
        String sceneKey = stepKey.substring("generate_scene:".length());
        return sceneMapper.update(null, new UpdateWrapper<ChapterGenerationSceneEntity>()
                .eq("generation_id", generation.getId()).eq("scene_key", sceneKey).eq("deleted", 0)
                .eq("scene_status", "running").set("scene_status", generationStatus)
                .setSql("version = version + 1").set("gmt_modified", LocalDateTime.now()));
    }

    private ChapterGenerationEntity generationForRun(Long runId) {
        return generationMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChapterGenerationEntity>()
                .eq(ChapterGenerationEntity::getAgentRunId, runId)
                .eq(ChapterGenerationEntity::getDeleted, 0));
    }

    private Long generationId(Long runId) {
        ChapterGenerationEntity generation = generationForRun(runId);
        return generation == null ? null : generation.getId();
    }
}
