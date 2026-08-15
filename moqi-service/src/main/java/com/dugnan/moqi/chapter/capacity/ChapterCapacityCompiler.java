package com.dugnan.moqi.chapter.capacity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityResult;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.EventWeight;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 从冻结 Brief 与已发布场景规划确定性编译章节容量基线。
 */
@Component
public class ChapterCapacityCompiler {

    public static final String EVALUATOR_VERSION = "chapter-capacity-v1";
    private static final int MINIMUM_SCENE_WORDS = 280;
    private static final int CAUSAL_NODE_WORDS = 100;
    private static final int STATE_CHANGE_WORDS = 80;
    private static final int HIGH_WEIGHT_BONUS_WORDS = 220;
    private static final int LOW_WEIGHT_REDUCTION_WORDS = 80;
    private static final int ABSOLUTE_SCENE_MINIMUM_WORDS = 180;
    private static final int ABSOLUTE_CHAPTER_MINIMUM_WORDS = 500;
    private static final int MINIMUM_RANGE_SPAN_WORDS = 300;
    private static final double SUGGESTED_MAXIMUM_MULTIPLIER = 1.35D;
    private static final double TOO_DENSE_THRESHOLD_MULTIPLIER = 0.85D;
    private static final double TOO_THIN_THRESHOLD_MULTIPLIER = 1.45D;
    private static final String SCENE_WEIGHT_CORE = "core";
    private static final String SCENE_WEIGHT_SUPPORTING = "supporting";
    private static final String SCENE_WEIGHT_TRANSITION = "transition";
    private static final String WEIGHT_HIGH = "high";
    private static final String WEIGHT_MEDIUM = "medium";
    private static final String WEIGHT_LOW = "low";
    private final ObjectMapper objectMapper;

    public ChapterCapacityCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompiledCapacity compile(ChapterPlanView plan, ChapterGenerationBrief brief, int targetWordCount) {
        List<ScenePlanView> scenes = plan.scenes().stream()
                .filter(item -> "planned".equals(item.content().status()))
                .sorted(Comparator.comparing(ScenePlanView::sequence).thenComparing(ScenePlanView::sceneKey))
                .toList();
        List<EventWeight> weights = new ArrayList<>();
        List<String> compressible = new ArrayList<>();
        List<String> required = new ArrayList<>();
        int recommendedMinimum = 0;
        for (ScenePlanView scene : scenes) {
            ScenePlanContent content = scene.content();
            int sceneMinimum = MINIMUM_SCENE_WORDS
                    + content.causalPreconditions().size() * CAUSAL_NODE_WORDS
                    + content.stateChanges().size() * STATE_CHANGE_WORDS;
            String weight = normalizeWeight(content.narrativeWeight());
            if (WEIGHT_HIGH.equals(weight)) {
                sceneMinimum += HIGH_WEIGHT_BONUS_WORDS;
            } else if (WEIGHT_LOW.equals(weight)) {
                sceneMinimum -= LOW_WEIGHT_REDUCTION_WORDS;
            }
            recommendedMinimum += Math.max(ABSOLUTE_SCENE_MINIMUM_WORDS, sceneMinimum);
            weights.add(new EventWeight(scene.sceneKey(), displayLabel(scene), weight,
                    "按已发布场景的叙事权重、因果前置与状态变化确定"));
            if (WEIGHT_LOW.equals(weight) || !content.optionalExpression().isEmpty()) {
                compressible.add(scene.sceneKey() + "：可压缩可选表达与过渡描写");
            }
            content.causalPreconditions().forEach(item -> required.add(scene.sceneKey() + "：" + item));
            content.stateChanges().forEach(item -> required.add(scene.sceneKey() + "：" + item));
        }
        int suggestedMinimum = Math.max(ABSOLUTE_CHAPTER_MINIMUM_WORDS, recommendedMinimum);
        int suggestedMaximum = Math.max(suggestedMinimum + MINIMUM_RANGE_SPAN_WORDS,
                (int) Math.ceil(suggestedMinimum * SUGGESTED_MAXIMUM_MULTIPLIER));
        String status;
        if (targetWordCount < suggestedMinimum * TOO_DENSE_THRESHOLD_MULTIPLIER) {
            status = ChapterCapacityModels.RESULT_TOO_DENSE;
        } else if (targetWordCount > suggestedMaximum * TOO_THIN_THRESHOLD_MULTIPLIER) {
            status = ChapterCapacityModels.RESULT_TOO_THIN;
        } else {
            status = ChapterCapacityModels.RESULT_FITS;
        }
        List<String> reasons = List.of("目标篇幅为 " + targetWordCount + " 字",
                "已发布规划包含 " + scenes.size() + " 个可生成场景和 " + required.size() + " 个不可省略因果/状态节点");
        List<String> splitSuggestions = ChapterCapacityModels.RESULT_TOO_DENSE.equals(status)
                ? List.of("可在不自动修改规划的前提下，按场景因果边界拆分章节") : List.of();
        List<String> actions = switch (status) {
            case ChapterCapacityModels.RESULT_TOO_DENSE -> List.of(
                    "revise_plan", "split_chapter", ChapterCapacityModels.DECISION_CONTINUE_LONG_CHAPTER);
            case ChapterCapacityModels.RESULT_TOO_THIN -> List.of("generate_naturally_short", "enrich_plan");
            default -> List.of("generate");
        };
        CapacityResult result = new CapacityResult(status, suggestedMinimum, suggestedMaximum, reasons, weights,
                compressible, required, splitSuggestions, actions, "fallback", null, false);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("chapterPlanVersionId", plan.id());
        snapshot.put("scenePlanNo", plan.planNo());
        snapshot.put("planVersion", plan.version());
        snapshot.put("targetWordCount", targetWordCount);
        snapshot.put("briefTemplateVersion", brief.templateVersion());
        snapshot.put("briefFingerprint", brief.fingerprint());
        snapshot.put("briefSourceRefs", brief.sourceRefs());
        snapshot.put("briefContent", brief.content());
        snapshot.put("scenes", scenes);
        String fingerprint = sha256(plan.id() + ":" + plan.version() + ":" + targetWordCount + ":"
                + brief.templateVersion() + ":" + brief.fingerprint() + ":" + json(scenes));
        return new CompiledCapacity(snapshot, result, fingerprint);
    }

    private String normalizeWeight(String value) {
        if (value == null) {
            return WEIGHT_MEDIUM;
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case SCENE_WEIGHT_CORE, WEIGHT_HIGH -> WEIGHT_HIGH;
            case SCENE_WEIGHT_SUPPORTING, WEIGHT_MEDIUM -> WEIGHT_MEDIUM;
            case SCENE_WEIGHT_TRANSITION, WEIGHT_LOW -> WEIGHT_LOW;
            default -> WEIGHT_MEDIUM;
        };
    }

    private String displayLabel(ScenePlanView scene) {
        return scene.content().title() == null || scene.content().title().isBlank()
                ? scene.sceneKey() : scene.content().title();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "容量评估输入无法序列化", exception);
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法计算容量评估输入指纹", exception);
        }
    }

    public record CompiledCapacity(Map<String, Object> sourceSnapshot, CapacityResult fallback, String fingerprint) {
    }
}
