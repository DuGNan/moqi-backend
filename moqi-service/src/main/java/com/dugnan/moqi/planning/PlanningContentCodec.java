package com.dugnan.moqi.planning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningModels.ForeshadowingAction;
import com.dugnan.moqi.planning.PlanningModels.NarrativePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 校验作品叙事规划和场景规划的稳定结构化内容。
 */
@Component
public class PlanningContentCodec {
    public static final int CURRENT_SCENE_CONTENT_SCHEMA_VERSION = 2;
    private static final Set<String> SCENE_STATUS = Set.of("planned", "disabled");
    private static final Set<String> FORESHADOWING_ACTIONS = Set.of("seed", "advance", "payoff");
    private static final Set<String> NARRATIVE_WEIGHTS = Set.of("core", "supporting", "transition");

    public NarrativePlanContent narrative(NarrativePlanContent content) {
        if (content == null) {
            throw invalid("作品叙事规划不能为空");
        }
        return new NarrativePlanContent(required(content.goal(), "goal", 2000), required(content.premise(), "premise", 4000),
                required(content.coreConflict(), "coreConflict", 2000), required(content.thematicIntent(), "thematicIntent", 1000),
                required(content.endingDirection(), "endingDirection", 2000), strings(content.constraints(), "constraints", 50, 500));
    }

    public List<ScenePlanContent> scenes(List<ScenePlanContent> scenes) {
        if (scenes == null || scenes.isEmpty() || scenes.size() > 50) {
            throw invalid("场景数量必须在 1 到 50 之间");
        }
        Set<String> keys = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        List<ScenePlanContent> normalized = new ArrayList<>();
        for (ScenePlanContent scene : scenes) {
            if (scene == null) {
                throw invalid("场景不能为空");
            }
            String key = required(scene.sceneKey(), "sceneKey", 128);
            Integer sequence = scene.sequence();
            if (sequence == null || sequence < 1 || !keys.add(key) || !sequences.add(sequence)) {
                throw invalid("sceneKey 与 sequence 必须在同一版本内唯一且 sequence 为正数");
            }
            String status = required(scene.status(), "status", 32);
            if (!SCENE_STATUS.contains(status)) {
                throw invalid("status 仅支持 planned 或 disabled");
            }
            String narrativeWeight = required(scene.narrativeWeight(), "narrativeWeight", 32);
            if (!NARRATIVE_WEIGHTS.contains(narrativeWeight)) {
                throw invalid("narrativeWeight 仅支持 core、supporting 或 transition");
            }
            normalized.add(new ScenePlanContent(key, sequence, required(scene.title(), "title", 200), scene.viewpointCharacter(),
                    required(scene.timeAnchor(), "timeAnchor", 500), scene.location(), required(scene.goal(), "goal", 2000),
                    required(scene.conflict(), "conflict", 2000), required(scene.emotion(), "emotion", 500),
                    required(scene.pacing(), "pacing", 500), references(scene.participants()), references(scene.requiredSettings()),
                    actions(scene.foreshadowingActions()), required(scene.expectedOutcome(), "expectedOutcome", 2000), status,
                    beatKeys(scene.outlineBeatKeys()), semanticStrings(scene.readerMustKnow(), "readerMustKnow"),
                    semanticStrings(scene.causalPreconditions(), "causalPreconditions"),
                    optional(scene.locationTransition(), "locationTransition", 1000),
                    semanticStrings(scene.stateChanges(), "stateChanges"),
                    semanticStrings(scene.continuityConstraints(), "continuityConstraints"), narrativeWeight,
                    semanticStrings(scene.optionalExpression(), "optionalExpression"),
                    semanticStrings(scene.doNotInvent(), "doNotInvent")));
        }
        for (int sequence = 1; sequence <= normalized.size(); sequence++) {
            if (!sequences.contains(sequence)) {
                throw invalid("sequence 必须从 1 开始连续递增");
            }
        }
        return List.copyOf(normalized);
    }

    private List<ForeshadowingAction> actions(List<ForeshadowingAction> actions) {
        if (actions == null) {
            return List.of();
        }
        List<ForeshadowingAction> result = new ArrayList<>();
        for (ForeshadowingAction action : actions) {
            if (action == null || !FORESHADOWING_ACTIONS.contains(action.action())) {
                throw invalid("伏笔动作必须是 seed、advance 或 payoff");
            }
            if (("advance".equals(action.action()) || "payoff".equals(action.action())) && action.foreshadowingItemId() == null) {
                throw invalid("推进或回收伏笔必须引用既有伏笔");
            }
            result.add(new ForeshadowingAction(action.action(), action.foreshadowingItemId(),
                    required(action.description(), "foreshadowing.description", 1000)));
        }
        return List.copyOf(result);
    }

    private List<PlanningModels.PlanReference> references(List<PlanningModels.PlanReference> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private List<String> beatKeys(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > 50) {
            throw invalid("outlineBeatKeys 数量超出限制");
        }
        Set<String> keys = new HashSet<>();
        for (String value : values) {
            String normalized = required(value, "outlineBeatKeys", 128);
            if (!keys.add(normalized)) {
                throw invalid("outlineBeatKeys 不能重复");
            }
        }
        return List.copyOf(keys);
    }

    private List<String> strings(List<String> values, String field, int maxCount, int maxLength) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > maxCount) {
            throw invalid(field + " 数量超出限制");
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(required(value, field, maxLength));
        }
        return List.copyOf(result);
    }

    private List<String> semanticStrings(List<String> values, String field) {
        return strings(values, field, 20, 500);
    }

    private String optional(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw invalid(field + " 长度超限");
        }
        return normalized;
    }

    private String required(String value, String field, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (!StringUtils.hasText(normalized) || normalized.length() > maxLength) {
            throw invalid(field + " 不能为空或长度超限");
        }
        return normalized;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.SCENE_PLAN_INVALID, message);
    }
}
