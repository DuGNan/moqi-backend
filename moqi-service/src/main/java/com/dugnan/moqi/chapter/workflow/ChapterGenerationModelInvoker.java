package com.dugnan.moqi.chapter.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.ChapterWordRange;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.SceneWordRange;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.llm.LlmCallContext;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 统一章节生成 Provider 创建、调用观测、流式终态校验与长度校正。
 */
@Component
public class ChapterGenerationModelInvoker {

    static final String WORKFLOW_TYPE = "scene_novel_generation";
    static final String SCENE_TEMPLATE_VERSION = "scene-novel-v4";
    static final String COHESION_TEMPLATE_VERSION = "chapter-cohesion-v1";

    private final UserConfigService userConfigService;
    private final LlmProviderFactory providerFactory;
    private final ChapterGenerationLengthPolicy lengthPolicy;
    private final ChapterGenerationPromptCompiler promptCompiler;
    private final ChapterGenerationCompletionHandler completionHandler;
    private final ObjectMapper objectMapper;

    public ChapterGenerationModelInvoker(
            UserConfigService userConfigService,
            LlmProviderFactory providerFactory,
            ChapterGenerationLengthPolicy lengthPolicy,
            ChapterGenerationPromptCompiler promptCompiler,
            ChapterGenerationCompletionHandler completionHandler,
            ObjectMapper objectMapper) {
        this.userConfigService = userConfigService;
        this.providerFactory = providerFactory;
        this.lengthPolicy = lengthPolicy;
        this.promptCompiler = promptCompiler;
        this.completionHandler = completionHandler;
        this.objectMapper = objectMapper;
    }

    public SceneInvocationContext prepareScene(ChapterGenerationEntity generation) {
        LlmExecutionConfig executionConfig = verifyExecutionConfig(generation);
        return new SceneInvocationContext(executionConfig,
                providerFactory.create(executionConfig.runtimeConfig()));
    }

    public AgentStepResult generateScene(
            ChapterGenerationEntity generation,
            ChapterGenerationSceneEntity scene,
            StoryContextSnapshot snapshot,
            SceneWordRange wordRange,
            SceneInvocationContext invocationContext,
            AgentStepExecutionContext context,
            String nextStep) {
        LlmProvider provider = providerFactory.createObserved(
                invocationContext.executionConfig(),
                LlmCallContext.builder(WORKFLOW_TYPE, "generate_scene")
                        .workId(generation.getWorkId()).chapterId(generation.getChapterId())
                        .generationSceneId(scene.getId()).agentRunId(context.runId()).agentStepId(context.stepId())
                        .logicalCallId("agent-step:" + context.stepId() + ":scene")
                        .promptTemplateVersion(SCENE_TEMPLATE_VERSION)
                        .sourceFingerprint(snapshot.contentHash())
                        .build());
        StringBuilder content = new StringBuilder();
        long started = System.nanoTime();
        LlmStreamCall call = null;
        try {
            completionHandler.sceneStarted(generation, scene);
            call = provider.stream(new LlmRequest(snapshot.toMessages(), options(
                    lengthPolicy.maxOutputTokens(wordRange.maximum(), provider.capabilities().maxOutputTokens()),
                    context.input())), event -> consumeSceneDelta(event, context, generation, scene, content));
            context.callRegistry().register(context.runId(), call);
            LlmStreamResult streamResult = requireCompleted(call.await());
            context.callRegistry().unregister(context.runId(), call);
            call = null;
            int actualWordCount = wordCount(content.toString());
            if (!wordRange.contains(actualWordCount)) {
                List<LlmMessage> messages = new ArrayList<>(snapshot.toMessages());
                messages.add(new LlmMessage(LlmRole.ASSISTANT, content.toString()));
                messages.add(new LlmMessage(LlmRole.USER,
                        promptCompiler.correctionInstruction(wordRange, actualWordCount)));
                content.setLength(0);
                call = provider.stream(new LlmRequest(messages, options(
                        lengthPolicy.maxOutputTokens(wordRange.maximum(), provider.capabilities().maxOutputTokens()),
                        context.input())), event -> consumeCorrectionDelta(event, context, content));
                context.callRegistry().register(context.runId(), call);
                streamResult = requireCompleted(call.await());
            }
            if (!StringUtils.hasText(content)) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "模型未返回场景正文");
            }
            return sceneResult(scene, snapshot, wordRange, content.toString(), streamResult,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), nextStep);
        } finally {
            context.callRegistry().unregister(context.runId(), call);
        }
    }

    public AgentStepResult cohereChapter(
            ChapterGenerationEntity generation,
            List<ChapterGenerationSceneEntity> scenes,
            int targetWordCount,
            AgentStepExecutionContext context) {
        LlmExecutionConfig executionConfig = verifyExecutionConfig(generation);
        String joined = scenes.stream().map(ChapterGenerationSceneEntity::getGeneratedContent)
                .filter(StringUtils::hasText).collect(java.util.stream.Collectors.joining("\n\n"));
        LlmProvider provider = providerFactory.createObserved(
                executionConfig,
                LlmCallContext.builder(WORKFLOW_TYPE, "cohere_chapter")
                        .workId(generation.getWorkId()).chapterId(generation.getChapterId())
                        .agentRunId(context.runId()).agentStepId(context.stepId())
                        .logicalCallId("agent-step:" + context.stepId() + ":cohere")
                        .promptTemplateVersion(COHESION_TEMPLATE_VERSION)
                        .sourceFingerprint(sha256(joined))
                        .build());
        ChapterWordRange wordRange = lengthPolicy.chapterWordRange(targetWordCount);
        LlmResponse response = provider.generate(new LlmRequest(List.of(
                new LlmMessage(LlmRole.SYSTEM, promptCompiler.cohesionInstruction(targetWordCount)),
                new LlmMessage(LlmRole.USER, joined)), options(lengthPolicy.maxOutputTokens(
                        Math.max(targetWordCount + targetWordCount / 5, targetWordCount),
                        provider.capabilities().maxOutputTokens()), context.input())));
        String content = response.content();
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "整章收束未返回正文");
        }
        if (!wordRange.contains(wordCount(content))) {
            response = provider.generate(new LlmRequest(List.of(
                    new LlmMessage(LlmRole.SYSTEM, promptCompiler.cohesionInstruction(targetWordCount)),
                    new LlmMessage(LlmRole.ASSISTANT, content),
                    new LlmMessage(LlmRole.USER,
                            promptCompiler.cohesionCorrectionInstruction(wordRange, wordCount(content)))),
                    options(lengthPolicy.maxOutputTokens(
                            wordRange.maximum(), provider.capabilities().maxOutputTokens()),
                            context.input())));
            content = response.content();
        }
        if (!StringUtils.hasText(content) || !wordRange.contains(wordCount(content))) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "整章收束字数未满足目标范围");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("content", content);
        output.put("modelCallId", response.metadata() == null ? null : response.metadata().modelCallId());
        return new AgentStepResult(output, Map.of(), ChapterGenerationStepPlanner.FINALIZE,
                response.metadata() == null || response.metadata().modelCallId() == null
                        ? null : String.valueOf(response.metadata().modelCallId()), null);
    }

    private LlmExecutionConfig verifyExecutionConfig(ChapterGenerationEntity generation) {
        LlmExecutionConfigDescriptor expected;
        try {
            expected = objectMapper.readValue(
                    generation.getExecutionConfigJson(), LlmExecutionConfigDescriptor.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "已持久化生成数据无法读取", exception);
        }
        LlmExecutionConfig current = userConfigService.requireAvailableExecutionConfig();
        if (!expected.equals(current.descriptor())) {
            throw new BusinessException(ErrorCode.GENERATION_CONFIG_STALE, "模型配置或凭据已变化，请创建新的生成批次");
        }
        return current;
    }

    private AgentStepResult sceneResult(
            ChapterGenerationSceneEntity scene,
            StoryContextSnapshot snapshot,
            SceneWordRange wordRange,
            String content,
            LlmStreamResult streamResult,
            long elapsedMillis,
            String nextStep) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("sceneId", scene.getId());
        output.put("contextSnapshotId", snapshot.id());
        Long modelCallId = streamResult.metadata() == null ? null : streamResult.metadata().modelCallId();
        output.put("modelCallId", modelCallId);
        output.put("content", content);
        output.put("targetWordCount", wordRange.target());
        output.put("minimumWordCount", wordRange.minimum());
        output.put("maximumWordCount", wordRange.maximum());
        output.put("elapsedMillis", elapsedMillis);
        putMetadata(output, streamResult.metadata());
        return new AgentStepResult(output, Map.of("lastSceneId", scene.getId()), nextStep,
                modelCallId == null ? null : String.valueOf(modelCallId), null);
    }

    private LlmOptions options(int maxOutputTokens, Map<String, Object> input) {
        Object value = input.get("temperature");
        Double temperature = value instanceof Number number ? number.doubleValue() : null;
        return new LlmOptions(maxOutputTokens, temperature, List.of(), LlmResponseFormat.TEXT);
    }

    private void consumeSceneDelta(
            LlmStreamEvent event,
            AgentStepExecutionContext context,
            ChapterGenerationEntity generation,
            ChapterGenerationSceneEntity scene,
            StringBuilder content) {
        if (event instanceof LlmStreamEvent.TextDelta delta && StringUtils.hasText(delta.text())
                && !context.callRegistry().isCancellationRequested(context.runId())) {
            content.append(delta.text());
            completionHandler.sceneDelta(generation, scene, delta.text());
        }
    }

    private void consumeCorrectionDelta(
            LlmStreamEvent event,
            AgentStepExecutionContext context,
            StringBuilder content) {
        if (event instanceof LlmStreamEvent.TextDelta delta && StringUtils.hasText(delta.text())
                && !context.callRegistry().isCancellationRequested(context.runId())) {
            content.append(delta.text());
        }
    }

    private LlmStreamResult requireCompleted(LlmStreamResult streamResult) {
        if (streamResult.status() == LlmStreamStatus.CANCELED) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "场景生成已取消");
        }
        if (streamResult.status() == LlmStreamStatus.FAILED) {
            throw new LlmProviderException(streamResult.error());
        }
        return streamResult;
    }

    private void putMetadata(Map<String, Object> output, LlmResponseMetadata metadata) {
        if (metadata != null) {
            output.put("finishReason", metadata.finishReason());
            output.put("providerRequestId", metadata.providerRequestId());
            output.put("inputTokens", metadata.inputTokens());
            output.put("outputTokens", metadata.outputTokens());
            output.put("totalTokens", metadata.totalTokens());
        }
    }

    private int wordCount(String content) {
        return StringUtils.hasText(content) ? content.trim().length() : 0;
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

    public record SceneInvocationContext(LlmExecutionConfig executionConfig, LlmProvider contextProvider) {
    }
}
