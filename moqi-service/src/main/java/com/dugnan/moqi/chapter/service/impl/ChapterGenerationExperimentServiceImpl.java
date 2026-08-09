package com.dugnan.moqi.chapter.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.ExperimentList;
import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.ExperimentView;
import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.RunExperimentRequest;
import com.dugnan.moqi.chapter.entity.ChapterGenerationExperimentEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationExperimentMapper;
import com.dugnan.moqi.chapter.service.ChapterGenerationExperimentService;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.ChapterWordRange;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.SceneWordRange;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmCallContext;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;
import com.dugnan.moqi.planning.PublishedScenePlanQueryPort;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 隔离执行整章一次生成和逐场景收束实验并持久化可审计结果。
 */
@Service
public class ChapterGenerationExperimentServiceImpl implements ChapterGenerationExperimentService {

    public static final String STRATEGY_WHOLE_CHAPTER_ONCE = "whole_chapter_once";
    public static final String STRATEGY_SCENE_THEN_COHERE = "scene_then_cohere";
    public static final String STRATEGY_LOOSE_STORY_INTENT = "loose_story_intent";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";
    private static final String WHOLE_TEMPLATE = "chapter-whole-once-v1";
    private static final String SCENE_TEMPLATE = "scene-novel-v3+chapter-cohesion-v1";
    private static final String LOOSE_STORY_INTENT_TEMPLATE = "chapter-loose-intent-v1";
    private static final String WORKFLOW_TYPE = "chapter_generation_experiment";
    private static final int DEFAULT_TARGET_WORD_COUNT = 3000;
    private static final int MIN_TARGET_WORD_COUNT = 500;
    private static final int MAX_TARGET_WORD_COUNT = 20000;
    private static final int MIN_SAMPLE_NO = 1;
    private static final int MAX_SAMPLE_NO = 100;
    private static final double MIN_TEMPERATURE = 0D;
    private static final double MAX_TEMPERATURE = 2D;
    private static final int MAX_STORY_INTENT_LENGTH = 2000;
    private static final Pattern GROUP_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final ChapterMapper chapterMapper;
    private final ChapterGenerationExperimentMapper experimentMapper;
    private final PublishedScenePlanQueryPort scenePlanQueryPort;
    private final UserConfigService userConfigService;
    private final LlmProviderFactory providerFactory;
    private final ObjectMapper objectMapper;
    private final ChapterGenerationLengthPolicy lengthPolicy;

    public ChapterGenerationExperimentServiceImpl(
            ChapterMapper chapterMapper,
            ChapterGenerationExperimentMapper experimentMapper,
            PublishedScenePlanQueryPort scenePlanQueryPort,
            UserConfigService userConfigService,
            LlmProviderFactory providerFactory,
            ObjectMapper objectMapper,
            ChapterGenerationLengthPolicy lengthPolicy) {
        this.chapterMapper = chapterMapper;
        this.experimentMapper = experimentMapper;
        this.scenePlanQueryPort = scenePlanQueryPort;
        this.userConfigService = userConfigService;
        this.providerFactory = providerFactory;
        this.objectMapper = objectMapper;
        this.lengthPolicy = lengthPolicy;
    }

    @Override
    public ExperimentView run(Long chapterId, RunExperimentRequest request) {
        validate(request);
        ChapterGenerationExperimentEntity existing = findExisting(chapterId, request);
        if (existing != null) {
            return view(existing);
        }
        ChapterEntity chapter = requireChapter(chapterId);
        ChapterPlanView chapterPlan = scenePlanQueryPort.loadCurrent(chapterId);
        List<ScenePlanView> scenes = orderedScenes(chapterPlan);
        LlmExecutionConfig executionConfig = userConfigService.requireAvailableExecutionConfig();
        int targetWordCount = resolveTargetWordCount(request.targetWordCount());
        String sceneRouteJson = json(scenes);
        String experimentInputJson = experimentInputJson(request, sceneRouteJson);
        String templateVersion = templateVersion(request.strategy());
        String inputFingerprint = sha256(json(Arrays.asList(
                request.strategy(),
                experimentInputJson,
                targetWordCount,
                request.temperature(),
                executionConfig.descriptor())));
        ChapterGenerationExperimentEntity experiment = createExperiment(
                chapter,
                chapterPlan,
                request,
                executionConfig,
                templateVersion,
                inputFingerprint,
                experimentInputJson);
        List<Long> modelCallIds = new ArrayList<>();
        List<String> sceneOutputs = new ArrayList<>();
        long started = System.nanoTime();
        try {
            String generatedContent;
            if (STRATEGY_WHOLE_CHAPTER_ONCE.equals(request.strategy())) {
                generatedContent = generateWholeChapter(
                            experiment,
                            scenes,
                            sceneRouteJson,
                            targetWordCount,
                            request.temperature(),
                            executionConfig,
                            modelCallIds);
            } else if (STRATEGY_LOOSE_STORY_INTENT.equals(request.strategy())) {
                generatedContent = generateFromLooseStoryIntent(
                        experiment,
                        request.storyIntent().trim(),
                        targetWordCount,
                        request.temperature(),
                        executionConfig,
                        modelCallIds);
            } else {
                generatedContent = generateByScenes(
                            experiment,
                            scenes,
                            sceneRouteJson,
                            targetWordCount,
                            request.temperature(),
                            executionConfig,
                            modelCallIds,
                            sceneOutputs);
            }
            complete(experiment, generatedContent, modelCallIds, sceneOutputs, started);
        } catch (RuntimeException exception) {
            fail(experiment, exception, modelCallIds, sceneOutputs, started);
        }
        return view(experiment);
    }

    @Override
    public ExperimentList list(Long chapterId, String experimentGroupKey) {
        if (!StringUtils.hasText(experimentGroupKey)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "experimentGroupKey 不能为空");
        }
        List<ChapterGenerationExperimentEntity> experiments = experimentMapper.selectList(
                new LambdaQueryWrapper<ChapterGenerationExperimentEntity>()
                        .eq(ChapterGenerationExperimentEntity::getChapterId, chapterId)
                        .eq(ChapterGenerationExperimentEntity::getExperimentGroupKey, experimentGroupKey.trim())
                        .eq(ChapterGenerationExperimentEntity::getDeleted, 0)
                        .orderByAsc(ChapterGenerationExperimentEntity::getStrategy)
                        .orderByAsc(ChapterGenerationExperimentEntity::getSampleNo));
        return new ExperimentList(experiments.stream().map(this::view).toList());
    }

    private String generateWholeChapter(
            ChapterGenerationExperimentEntity experiment,
            List<ScenePlanView> scenes,
            String sceneRouteJson,
            int targetWordCount,
            Double temperature,
            LlmExecutionConfig executionConfig,
            List<Long> modelCallIds) {
        String instruction = "你是小说作者。请严格按照给定的全部场景顺序，一次性创作完整章节。"
                + "必须保留每个场景的目标、冲突、结果和关键事件，并用自然动作、因果和时空转换连接相邻场景。"
                + "禁止输出标题、场景编号、分析或说明；避免复述台词、交接动作和意象。目标约 "
                + targetWordCount + " 个中文字符。";
        String input = "有序场景规划如下（必须按数组顺序执行）：\n" + sceneRouteJson;
        return call(
                experiment,
                "whole_chapter_once",
                WHOLE_TEMPLATE,
                executionConfig,
                new LlmRequest(
                        List.of(
                                new LlmMessage(LlmRole.SYSTEM, instruction),
                                new LlmMessage(LlmRole.USER, input)),
                        options(targetWordCount, temperature, executionConfig)),
                modelCallIds);
    }

    private String generateByScenes(
            ChapterGenerationExperimentEntity experiment,
            List<ScenePlanView> scenes,
            String sceneRouteJson,
            int targetWordCount,
            Double temperature,
            LlmExecutionConfig executionConfig,
            List<Long> modelCallIds,
            List<String> sceneOutputs) {
        String previousContent = null;
        for (int index = 0; index < scenes.size(); index++) {
            ScenePlanView scene = scenes.get(index);
            SceneWordRange range = lengthPolicy.sceneWordRange(
                    targetWordCount, scenes.size(), index + 1);
            String sceneInput = sceneInput(sceneRouteJson, scene, previousContent, range);
            String content = call(
                    experiment,
                    "generate_scene_" + (index + 1),
                    "scene-novel-v3",
                    executionConfig,
                    new LlmRequest(
                            List.of(
                                    new LlmMessage(LlmRole.SYSTEM, sceneInstruction(range)),
                                    new LlmMessage(LlmRole.USER, sceneInput)),
                            new LlmOptions(
                                    lengthPolicy.maxOutputTokens(
                                            range.maximum(),
                                            null),
                                    temperature,
                                    List.of(),
                                    LlmResponseFormat.TEXT)),
                    modelCallIds);
            sceneOutputs.add(content);
            previousContent = content;
        }
        String joinedScenes = String.join("\n\n", sceneOutputs);
        String cohesiveContent = call(
                experiment,
                "cohere_chapter",
                "chapter-cohesion-v1",
                executionConfig,
                new LlmRequest(
                        List.of(
                                new LlmMessage(LlmRole.SYSTEM, cohesionInstruction(targetWordCount)),
                                new LlmMessage(LlmRole.USER,
                                        "有序场景规划：\n" + sceneRouteJson
                                                + "\n\n逐场景原始正文：\n" + joinedScenes)),
                        options(targetWordCount, temperature, executionConfig)),
                modelCallIds);
        ChapterWordRange chapterWordRange = lengthPolicy.chapterWordRange(targetWordCount);
        if (chapterWordRange.contains(wordCount(cohesiveContent))) {
            return cohesiveContent;
        }
        return call(
                experiment,
                "correct_chapter_length",
                "chapter-cohesion-v1",
                executionConfig,
                new LlmRequest(
                        List.of(
                                new LlmMessage(LlmRole.SYSTEM, cohesionInstruction(targetWordCount)),
                                new LlmMessage(LlmRole.ASSISTANT, cohesiveContent),
                                new LlmMessage(
                                        LlmRole.USER,
                                        cohesionCorrectionInstruction(
                                                chapterWordRange,
                                                wordCount(cohesiveContent)))),
                        options(targetWordCount, temperature, executionConfig)),
                modelCallIds);
    }

    private String generateFromLooseStoryIntent(
            ChapterGenerationExperimentEntity experiment,
            String storyIntent,
            int targetWordCount,
            Double temperature,
            LlmExecutionConfig executionConfig,
            List<Long> modelCallIds) {
        String instruction = "你是一名擅长沉浸感和叙事节奏的网文作者。用户给出的是本章想表达的故事意图，"
                + "不是必须逐项执行的场景清单。请在不背离人物关系、核心冲突和结局方向的前提下自由创作完整章节，"
                + "自行决定场景数量、段落顺序、过渡方式和细节取舍。让时间、地点、环境变化、人物位置和行动原因"
                + "自然出现在叙事中，给人物留出观察、反应和情绪沉淀的空间；不要为了覆盖情节而匆忙串场。"
                + "避免总结式叙述、模板化转折和凭空增加会改变后续故事走向的重大设定。"
                + "只输出小说正文，不输出标题、分析、场景编号或创作说明。目标约 "
                + targetWordCount + " 个中文字符。";
        return call(
                experiment,
                STRATEGY_LOOSE_STORY_INTENT,
                LOOSE_STORY_INTENT_TEMPLATE,
                executionConfig,
                new LlmRequest(
                        List.of(
                                new LlmMessage(LlmRole.SYSTEM, instruction),
                                new LlmMessage(LlmRole.USER, "本章故事意图：\n" + storyIntent)),
                        options(targetWordCount, temperature, executionConfig)),
                modelCallIds);
    }

    private String call(
            ChapterGenerationExperimentEntity experiment,
            String operation,
            String templateVersion,
            LlmExecutionConfig executionConfig,
            LlmRequest request,
            List<Long> modelCallIds) {
        LlmProvider provider = providerFactory.createObserved(
                executionConfig,
                LlmCallContext.builder(WORKFLOW_TYPE, operation)
                        .workId(experiment.getWorkId())
                        .chapterId(experiment.getChapterId())
                        .logicalCallId("generation-experiment:" + experiment.getId() + ":" + operation)
                        .promptTemplateVersion(templateVersion)
                        .sourceFingerprint(experiment.getInputFingerprint())
                        .build());
        LlmResponse response = provider.generate(request);
        if (response.metadata() != null && response.metadata().modelCallId() != null) {
            modelCallIds.add(response.metadata().modelCallId());
        }
        if (!StringUtils.hasText(response.content())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "模型未返回实验正文");
        }
        return response.content().trim();
    }

    private LlmOptions options(
            int targetWordCount,
            Double temperature,
            LlmExecutionConfig executionConfig) {
        int maximumWordCount = lengthPolicy.chapterWordRange(targetWordCount).maximum();
        return new LlmOptions(
                lengthPolicy.maxOutputTokens(
                        maximumWordCount,
                        null),
                temperature,
                List.of(),
                LlmResponseFormat.TEXT);
    }

    private String sceneInput(
            String sceneRouteJson,
            ScenePlanView scene,
            String previousContent,
            SceneWordRange range) {
        String previous = StringUtils.hasText(previousContent) ? previousContent : "无，这是本章第一场。";
        return "整章有序场景路线：\n" + sceneRouteJson
                + "\n\n当前场景：\n" + json(scene)
                + "\n\n完整上一场正文：\n" + previous
                + "\n\n必须从上一场最后动作和未完成目标自然继续。本场正文限制为 "
                + range.minimum() + " 至 " + range.maximum() + " 个中文字符。";
    }

    private String sceneInstruction(SceneWordRange range) {
        return "你是小说作者。只输出当前场景正文。不得复述已发生事件或台词；持续维护时间、地点、人物位置、"
                + "伤势、道具和未完成目标的连续性，不得改变规划中的关键事件。字数必须在 "
                + range.minimum() + " 至 " + range.maximum() + " 个中文字符之间。";
    }

    private String cohesionInstruction(int targetWordCount) {
        return "你是整章小说编辑。将逐场景正文收束为一篇完整章节，只允许补过渡、消重复、统一节奏并修复连续性；"
                + "不得删除、改变或新增规划关键事件和权威事实。只输出正文，目标约 "
                + targetWordCount + " 个中文字符。";
    }

    private String cohesionCorrectionInstruction(
            ChapterWordRange wordRange,
            int actualWordCount) {
        String action = actualWordCount < wordRange.minimum() ? "扩写" : "压缩";
        return "上一稿共 " + actualWordCount + " 个中文字符。请在不改变场景规划、关键事件和权威事实的前提下"
                + action + "，严格控制在 " + wordRange.minimum() + " 至 " + wordRange.maximum()
                + " 个中文字符之间；只输出修订后的完整整章正文。";
    }

    private ChapterGenerationExperimentEntity createExperiment(
            ChapterEntity chapter,
            ChapterPlanView chapterPlan,
            RunExperimentRequest request,
            LlmExecutionConfig executionConfig,
            String templateVersion,
            String inputFingerprint,
            String sceneRouteJson) {
        ChapterGenerationExperimentEntity experiment = new ChapterGenerationExperimentEntity();
        experiment.setWorkId(chapter.getWorkId());
        experiment.setChapterId(chapter.getId());
        experiment.setChapterPlanVersionId(chapterPlan.id());
        experiment.setExperimentGroupKey(request.experimentGroupKey().trim());
        experiment.setStrategy(request.strategy());
        experiment.setSampleNo(request.sampleNo());
        experiment.setExperimentStatus(STATUS_RUNNING);
        experiment.setTemplateVersion(templateVersion);
        experiment.setInputFingerprint(inputFingerprint);
        experiment.setProvider(executionConfig.descriptor().provider());
        experiment.setModel(executionConfig.descriptor().model());
        experiment.setConfigVersion(executionConfig.descriptor().configVersion());
        experiment.setCredentialVersion(executionConfig.descriptor().credentialVersion());
        experiment.setSceneRouteJson(sceneRouteJson);
        experiment.setDeleted(0);
        experiment.setVersion(0);
        experimentMapper.insert(experiment);
        return experiment;
    }

    private void complete(
            ChapterGenerationExperimentEntity experiment,
            String generatedContent,
            List<Long> modelCallIds,
            List<String> sceneOutputs,
            long started) {
        experiment.setExperimentStatus(STATUS_COMPLETED);
        experiment.setModelCallIdsJson(json(modelCallIds));
        experiment.setRawSceneOutputsJson(sceneOutputs.isEmpty() ? null : json(sceneOutputs));
        experiment.setGeneratedContent(generatedContent);
        experiment.setWordCount(wordCount(generatedContent));
        experiment.setElapsedMillis(Duration.ofNanos(System.nanoTime() - started).toMillis());
        experiment.setErrorMessage(null);
        experimentMapper.updateById(experiment);
    }

    private void fail(
            ChapterGenerationExperimentEntity experiment,
            RuntimeException exception,
            List<Long> modelCallIds,
            List<String> sceneOutputs,
            long started) {
        experiment.setExperimentStatus(STATUS_FAILED);
        experiment.setModelCallIdsJson(json(modelCallIds));
        experiment.setRawSceneOutputsJson(sceneOutputs.isEmpty() ? null : json(sceneOutputs));
        experiment.setElapsedMillis(Duration.ofNanos(System.nanoTime() - started).toMillis());
        experiment.setErrorMessage(safeMessage(exception));
        experimentMapper.updateById(experiment);
    }

    private ChapterGenerationExperimentEntity findExisting(Long chapterId, RunExperimentRequest request) {
        return experimentMapper.selectOne(
                new LambdaQueryWrapper<ChapterGenerationExperimentEntity>()
                        .eq(ChapterGenerationExperimentEntity::getChapterId, chapterId)
                        .eq(ChapterGenerationExperimentEntity::getExperimentGroupKey,
                                request.experimentGroupKey().trim())
                        .eq(ChapterGenerationExperimentEntity::getStrategy, request.strategy())
                        .eq(ChapterGenerationExperimentEntity::getSampleNo, request.sampleNo())
                        .eq(ChapterGenerationExperimentEntity::getDeleted, 0));
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private List<ScenePlanView> orderedScenes(ChapterPlanView chapterPlan) {
        if (chapterPlan == null || chapterPlan.scenes() == null || chapterPlan.scenes().isEmpty()) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "当前章节没有已发布场景规划");
        }
        return chapterPlan.scenes().stream()
                .sorted(Comparator.comparing(ScenePlanView::sequence))
                .toList();
    }

    private String templateVersion(String strategy) {
        if (STRATEGY_WHOLE_CHAPTER_ONCE.equals(strategy)) {
            return WHOLE_TEMPLATE;
        }
        if (STRATEGY_LOOSE_STORY_INTENT.equals(strategy)) {
            return LOOSE_STORY_INTENT_TEMPLATE;
        }
        return SCENE_TEMPLATE;
    }

    private String experimentInputJson(RunExperimentRequest request, String sceneRouteJson) {
        if (STRATEGY_LOOSE_STORY_INTENT.equals(request.strategy())) {
            return json(Map.of("storyIntent", request.storyIntent().trim()));
        }
        return sceneRouteJson;
    }

    private int resolveTargetWordCount(Integer value) {
        if (value == null) {
            return DEFAULT_TARGET_WORD_COUNT;
        }
        if (value < MIN_TARGET_WORD_COUNT || value > MAX_TARGET_WORD_COUNT) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "targetWordCount 必须在 500 到 20000 之间");
        }
        return value;
    }

    private void validate(RunExperimentRequest request) {
        if (request == null
                || !StringUtils.hasText(request.experimentGroupKey())
                || !GROUP_KEY_PATTERN.matcher(request.experimentGroupKey().trim()).matches()) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "experimentGroupKey 仅支持 1 到 64 位字母、数字、点、下划线和连字符");
        }
        if (!STRATEGY_WHOLE_CHAPTER_ONCE.equals(request.strategy())
                && !STRATEGY_SCENE_THEN_COHERE.equals(request.strategy())
                && !STRATEGY_LOOSE_STORY_INTENT.equals(request.strategy())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "strategy 不支持");
        }
        if (isLooseStoryIntentInvalid(request)) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "loose_story_intent 的 storyIntent 必须为 1 到 2000 个字符");
        }
        if (isSampleNoInvalid(request.sampleNo())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "sampleNo 必须在 1 到 100 之间");
        }
        resolveTargetWordCount(request.targetWordCount());
        if (isTemperatureInvalid(request.temperature())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "temperature 必须在 0 到 2 之间");
        }
    }

    private boolean isSampleNoInvalid(Integer sampleNo) {
        return sampleNo == null || sampleNo < MIN_SAMPLE_NO || sampleNo > MAX_SAMPLE_NO;
    }

    private boolean isTemperatureInvalid(Double temperature) {
        return temperature != null
                && (temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE);
    }

    private boolean isLooseStoryIntentInvalid(RunExperimentRequest request) {
        if (!STRATEGY_LOOSE_STORY_INTENT.equals(request.strategy())) {
            return false;
        }
        return !StringUtils.hasText(request.storyIntent())
                || request.storyIntent().trim().length() > MAX_STORY_INTENT_LENGTH;
    }

    private ExperimentView view(ChapterGenerationExperimentEntity experiment) {
        return new ExperimentView(
                experiment.getId(),
                experiment.getWorkId(),
                experiment.getChapterId(),
                experiment.getChapterPlanVersionId(),
                experiment.getExperimentGroupKey(),
                experiment.getStrategy(),
                experiment.getSampleNo(),
                experiment.getExperimentStatus(),
                experiment.getTemplateVersion(),
                experiment.getInputFingerprint(),
                experiment.getProvider(),
                experiment.getModel(),
                experiment.getConfigVersion(),
                experiment.getCredentialVersion(),
                experiment.getSceneRouteJson(),
                readModelCallIds(experiment.getModelCallIdsJson()),
                experiment.getRawSceneOutputsJson(),
                experiment.getGeneratedContent(),
                experiment.getWordCount(),
                experiment.getElapsedMillis(),
                experiment.getErrorMessage(),
                experiment.getGmtCreate(),
                experiment.getGmtModified());
    }

    private List<Long> readModelCallIds(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "实验模型调用引用无法读取", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "实验数据无法序列化", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SHA-256 不可用", exception);
        }
    }

    private int wordCount(String content) {
        return content.replaceAll("\\s", "").length();
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception instanceof BusinessException ? exception.getMessage() : "实验模型调用失败";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
