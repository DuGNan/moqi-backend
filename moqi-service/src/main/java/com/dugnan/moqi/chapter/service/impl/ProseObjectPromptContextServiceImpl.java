package com.dugnan.moqi.chapter.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.service.ProseObjectPromptContextService;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService.ProseObjectTarget;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-09-04
 * @description 将正文对象及其冻结生成依据编译为不泄露内部字段的自然语言上下文。
 */
@Service
public class ProseObjectPromptContextServiceImpl implements ProseObjectPromptContextService {

    private static final int MAX_DRAFT_LENGTH = 100000;
    private static final List<Map.Entry<String, String>> OUTLINE_LABELS = List.of(
            Map.entry("chapterPurpose", "章节作用"),
            Map.entry("chapterGoal", "本章目标"),
            Map.entry("coreConflict", "核心冲突"),
            Map.entry("openingConditions", "开场状态"),
            Map.entry("requiredEndingState", "必须达到的结尾状态"));
    private static final List<Map.Entry<String, String>> SCENE_LABELS = List.of(
            Map.entry("eventCausality", "必须事件与因果"),
            Map.entry("stateChanges", "人物与故事状态变化"));
    private static final List<Map.Entry<String, String>> CONSTRAINT_LABELS = List.of(
            Map.entry("creativeFreedom", "允许调整的范围"),
            Map.entry("prohibitedInventions", "不得编造或改变的内容"));

    private final ProseObjectTargetService targetService;
    private final ChapterGenerationMapper generationMapper;
    private final ObjectMapper objectMapper;

    public ProseObjectPromptContextServiceImpl(
            ProseObjectTargetService targetService,
            ChapterGenerationMapper generationMapper,
            ObjectMapper objectMapper) {
        this.targetService = targetService;
        this.generationMapper = generationMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public FrozenProseObjectContext freeze(Long chapterId, String objectId, ProseObjectDraft draft) {
        ProseObjectTarget target = targetService.resolve(chapterId, objectId);
        validateDraft(target, draft);
        return new FrozenProseObjectContext(target, render(target, draft, readBasis(target)));
    }

    private void validateDraft(ProseObjectTarget target, ProseObjectDraft draft) {
        if (draft == null) {
            return;
        }
        if (draft.baseVersion() == null || !StringUtils.hasText(draft.baseContentHash())
                || draft.content() == null || draft.content().length() > MAX_DRAFT_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未保存草稿必须包含有效的正文基线和内容");
        }
        if (!Objects.equals(target.version(), draft.baseVersion())
                || !Objects.equals(target.contentHash(), draft.baseContentHash())) {
            throw new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, "未保存草稿对应的正文基线已经变化");
        }
    }

    private FrozenBasis readBasis(ProseObjectTarget target) {
        if (target.sourceGenerationId() == null) {
            return null;
        }
        ChapterGenerationEntity generation = generationMapper.selectById(target.sourceGenerationId());
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted())
                || !StringUtils.hasText(generation.getBasisSnapshotJson())) {
            return null;
        }
        try {
            return new FrozenBasis(
                    objectMapper.readTree(generation.getBasisSnapshotJson()),
                    generation.getGeneratedContent());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "正文对象的创建依据无法读取", exception);
        }
    }

    private String render(ProseObjectTarget target, ProseObjectDraft draft, FrozenBasis frozenBasis) {
        StringBuilder result = new StringBuilder(target.promptText());
        if (draft != null) {
            result.append("\n\n【作者本轮提供的未保存编辑器草稿】\n")
                    .append("这份草稿只用于本轮讨论或生成修改提案，不代表已经保存、采纳或发布。")
                    .append("回答时优先分析这份草稿，并把上面的已保存正文作为基线。\n")
                    .append(draft.content());
        }
        appendBasis(result, target, frozenBasis);
        result.append("\n\n当前作者已经确认的作品资料和当前有效规划由其他上下文消息提供。")
                .append("创建时依据只解释这份正文为何这样写；两者不一致时，必须指出差异并以作者当前确认内容为准，")
                .append("不得静默混合，也不得自行保存、采纳或更新规划。");
        return result.toString();
    }

    private void appendBasis(StringBuilder result, ProseObjectTarget target, FrozenBasis frozenBasis) {
        result.append("\n\n【这份正文创建时采用的冻结依据】\n");
        JsonNode basis = frozenBasis == null ? null : frozenBasis.basis();
        if (basis == null || !basis.isObject()) {
            result.append("没有可追溯的完整创建依据；不得自行补造缺失设定。");
            return;
        }
        JsonNode brief = basis.path("chapterGenerationBrief");
        appendKnownFields(result, brief, OUTLINE_LABELS);
        appendKnownFields(result, brief, SCENE_LABELS);
        appendValues(result, "人物约束", brief.path("characterConstraints"));
        appendValues(result, "相关设定与名词", brief.path("entityExplanations"));
        appendKnownFields(result, brief, CONSTRAINT_LABELS);
        JsonNode previous = basis.path("currentProseBasis");
        if (!previous.isObject()) {
            previous = basis.path("baseGeneration");
        }
        appendValues(result, "创建时参考的前文", previous.path("content"));
        String generated = frozenBasis.generatedContent();
        if (StringUtils.hasText(generated) && !Objects.equals(generated, target.content())) {
            result.append("\n- 版本关系：当前保存内容在创建后经过作者编辑，冻结依据仍只代表创建当时。");
        }
    }

    private void appendKnownFields(
            StringBuilder result,
            JsonNode source,
            List<Map.Entry<String, String>> labels) {
        if (source == null || !source.isObject()) {
            return;
        }
        labels.forEach(entry -> appendValues(result, entry.getValue(), source.path(entry.getKey())));
    }

    private void appendValues(StringBuilder result, String label, JsonNode node) {
        List<String> values = new ArrayList<>();
        collectValues(node, values);
        if (!values.isEmpty()) {
            result.append("\n- ").append(label).append("：").append(String.join("；", values));
        }
    }

    private void collectValues(JsonNode node, List<String> values) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isValueNode()) {
            String value = node.asText();
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
            return;
        }
        node.elements().forEachRemaining(child -> collectValues(child, values));
    }

    private record FrozenBasis(JsonNode basis, String generatedContent) {
    }
}
