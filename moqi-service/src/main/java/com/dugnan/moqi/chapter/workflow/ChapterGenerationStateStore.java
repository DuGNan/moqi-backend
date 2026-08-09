package com.dugnan.moqi.chapter.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 集中处理章节正文生成批次与场景候选的查询和状态持久化。
 */
@Component
public class ChapterGenerationStateStore {

    static final String STATUS_QUEUED = "queued";
    static final String STATUS_RUNNING = "running";
    static final String STATUS_PREVIEW = "preview";
    static final String ASSEMBLY_COHESIVE_CHAPTER = "cohesive_chapter";
    static final String COHESION_COMPLETED = "completed";
    static final String SCENE_PENDING = "pending";
    static final String SCENE_RUNNING = "running";
    static final String SCENE_COMPLETED = "completed";
    static final String SCENE_COPIED = "copied";
    static final String COHESION_TEMPLATE_VERSION = "chapter-cohesion-v1";
    private static final String RESULT_SKIPPED = "skipped";

    private final ChapterGenerationMapper generationMapper;
    private final ChapterGenerationSceneMapper sceneMapper;

    public ChapterGenerationStateStore(
            ChapterGenerationMapper generationMapper,
            ChapterGenerationSceneMapper sceneMapper) {
        this.generationMapper = generationMapper;
        this.sceneMapper = sceneMapper;
    }

    public ChapterGenerationEntity requireGeneration(Long generationId) {
        ChapterGenerationEntity generation = generationMapper.selectById(generationId);
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted())) {
            throw new BusinessException(ErrorCode.GENERATION_NOT_FOUND, "生成批次不存在");
        }
        return generation;
    }

    public ChapterGenerationSceneEntity requireScene(Long generationId, String sceneKey) {
        ChapterGenerationSceneEntity scene = sceneMapper.selectOne(
                new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                        .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                        .eq(ChapterGenerationSceneEntity::getSceneKey, sceneKey)
                        .eq(ChapterGenerationSceneEntity::getDeleted, 0));
        if (scene == null) {
            throw new BusinessException(ErrorCode.GENERATION_SCENE_NOT_FOUND, "场景候选不存在");
        }
        return scene;
    }

    public List<ChapterGenerationSceneEntity> scenes(Long generationId) {
        return sceneMapper.selectList(new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                .eq(ChapterGenerationSceneEntity::getDeleted, 0)
                .orderByAsc(ChapterGenerationSceneEntity::getSequenceNo));
    }

    public List<ChapterGenerationSceneEntity> completedScenes(Long generationId) {
        List<ChapterGenerationSceneEntity> scenes = scenes(generationId);
        if (scenes.stream().anyMatch(scene -> !isCompleted(scene))) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "仍有场景候选未完成");
        }
        return scenes;
    }

    public ChapterGenerationSceneEntity nextScene(Long generationId, int sequenceNo) {
        return sceneMapper.selectList(new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                .gt(ChapterGenerationSceneEntity::getSequenceNo, sequenceNo)
                .eq(ChapterGenerationSceneEntity::getDeleted, 0)
                .orderByAsc(ChapterGenerationSceneEntity::getSequenceNo)).stream().findFirst().orElse(null);
    }

    public List<ChapterGenerationSceneEntity> previousCompletedScenes(Long generationId, int sequenceNo) {
        return sceneMapper.selectList(new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                .lt(ChapterGenerationSceneEntity::getSequenceNo, sequenceNo)
                .in(ChapterGenerationSceneEntity::getSceneStatus, List.of(SCENE_COMPLETED, SCENE_COPIED))
                .eq(ChapterGenerationSceneEntity::getDeleted, 0)
                .orderByAsc(ChapterGenerationSceneEntity::getSequenceNo));
    }

    public int sceneCount(Long generationId) {
        return Math.toIntExact(sceneMapper.selectCount(new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                .eq(ChapterGenerationSceneEntity::getDeleted, 0)));
    }

    public ChapterGenerationEntity markStarted(Long generationId) {
        generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generationId).eq("generation_status", STATUS_QUEUED)
                .set("generation_status", STATUS_RUNNING).setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now()));
        return requireGeneration(generationId);
    }

    public void markSceneRunning(ChapterGenerationSceneEntity scene, Long snapshotId) {
        int changed = sceneMapper.update(null, new UpdateWrapper<ChapterGenerationSceneEntity>()
                .eq("id", scene.getId()).eq("version", scene.getVersion())
                .in("scene_status", List.of(SCENE_PENDING, "failed"))
                .set("context_snapshot_id", snapshotId).set("scene_status", SCENE_RUNNING)
                .set("version", scene.getVersion() + 1).set("gmt_modified", LocalDateTime.now()));
        requireSingleUpdate(changed, "场景生成状态已变化");
    }

    public ChapterGenerationSceneEntity applySceneResult(
            Long generationId,
            AgentStepResult result) {
        Long sceneId = longValue(result.outputSummary().get("sceneId"));
        if (Boolean.TRUE.equals(result.outputSummary().get(RESULT_SKIPPED))) {
            return null;
        }
        ChapterGenerationSceneEntity scene = sceneId == null ? null : sceneMapper.selectById(sceneId);
        if (scene == null || !generationId.equals(scene.getGenerationId())) {
            throw new BusinessException(ErrorCode.GENERATION_SCENE_NOT_FOUND, "场景候选不存在");
        }
        if (SCENE_COMPLETED.equals(scene.getSceneStatus())) {
            return null;
        }
        String content = String.valueOf(result.outputSummary().get("content"));
        int changed = sceneMapper.update(null, new UpdateWrapper<ChapterGenerationSceneEntity>()
                .eq("id", sceneId).eq("version", scene.getVersion()).eq("scene_status", SCENE_RUNNING)
                .set("generated_content", content).set("content_hash", sha256(content))
                .set("word_count", wordCount(content))
                .set("model_call_id", longValue(result.outputSummary().get("modelCallId")))
                .set("finish_reason", stringValue(result.outputSummary().get("finishReason")))
                .set("input_tokens", integerValue(result.outputSummary().get("inputTokens")))
                .set("output_tokens", integerValue(result.outputSummary().get("outputTokens")))
                .set("total_tokens", integerValue(result.outputSummary().get("totalTokens")))
                .set("elapsed_millis", longValue(result.outputSummary().get("elapsedMillis")))
                .set("scene_status", SCENE_COMPLETED).set("version", scene.getVersion() + 1)
                .set("gmt_modified", LocalDateTime.now()));
        requireSingleUpdate(changed, "场景候选已被并发修改");
        return scene;
    }

    public void markCohesionRunning(Long generationId) {
        generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generationId).eq("generation_status", STATUS_RUNNING)
                .in("cohesion_status", List.of("pending", "failed"))
                .set("cohesion_status", STATUS_RUNNING).setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now()));
    }

    public void applyCohesionResult(Long generationId, AgentStepResult result) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        String content = stringValue(result.outputSummary().get("content"));
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "整章收束正文为空");
        }
        int changed = generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generationId).eq("version", generation.getVersion())
                .eq("generation_status", STATUS_RUNNING)
                .set("generated_content", content).set("word_count", wordCount(content))
                .set("cohesion_status", COHESION_COMPLETED)
                .set("cohesion_model_call_id", longValue(result.outputSummary().get("modelCallId")))
                .set("cohesion_template_version", COHESION_TEMPLATE_VERSION)
                .set("version", generation.getVersion() + 1).set("gmt_modified", LocalDateTime.now()));
        requireSingleUpdate(changed, "整章收束结果已被并发修改");
    }

    public ChapterGenerationEntity markFailed(
            Long generationId,
            String stepKey,
            String generatePrefix,
            String cohereStep) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        UpdateWrapper<ChapterGenerationEntity> update = new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generationId).eq("generation_status", STATUS_RUNNING)
                .set("generation_status", "failed").setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now());
        if (cohereStep.equals(stepKey)) {
            update.set("cohesion_status", "failed");
        }
        generationMapper.update(null, update);
        if (stepKey.startsWith(generatePrefix)) {
            sceneMapper.update(null, new UpdateWrapper<ChapterGenerationSceneEntity>()
                    .eq("generation_id", generationId).eq("scene_key", stepKey.substring(generatePrefix.length()))
                    .in("scene_status", List.of(SCENE_PENDING, SCENE_RUNNING))
                    .set("scene_status", "failed").setSql("version = version + 1")
                    .set("gmt_modified", LocalDateTime.now()));
        }
        return generation;
    }

    public ChapterGenerationEntity finalizeGeneration(Long generationId) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        List<ChapterGenerationSceneEntity> scenes = completedScenes(generationId);
        if (scenes.isEmpty()) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "仍有场景候选未完成");
        }
        boolean cohesive = ASSEMBLY_COHESIVE_CHAPTER.equals(generation.getContentAssemblyMode());
        if (cohesive && !COHESION_COMPLETED.equals(generation.getCohesionStatus())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "整章收束尚未完成");
        }
        String content = cohesive ? generation.getGeneratedContent() : joinScenes(scenes);
        int changed = generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generationId).eq("version", generation.getVersion())
                .eq("generation_status", STATUS_RUNNING)
                .set("generated_content", content).set("word_count", wordCount(content))
                .set("generation_status", STATUS_PREVIEW).set("version", generation.getVersion() + 1)
                .set("gmt_modified", LocalDateTime.now()));
        requireSingleUpdate(changed, "生成批次已被并发修改");
        return generation;
    }

    public String joinScenes(List<ChapterGenerationSceneEntity> scenes) {
        return scenes.stream().map(ChapterGenerationSceneEntity::getGeneratedContent)
                .filter(StringUtils::hasText).collect(Collectors.joining("\n\n"));
    }

    private boolean isCompleted(ChapterGenerationSceneEntity scene) {
        return SCENE_COMPLETED.equals(scene.getSceneStatus()) || SCENE_COPIED.equals(scene.getSceneStatus());
    }

    private void requireSingleUpdate(int changed, String message) {
        if (changed != 1) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, message);
        }
    }

    private int wordCount(String content) {
        return StringUtils.hasText(content) ? content.trim().length() : 0;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SHA-256 不可用", exception);
        }
    }
}
