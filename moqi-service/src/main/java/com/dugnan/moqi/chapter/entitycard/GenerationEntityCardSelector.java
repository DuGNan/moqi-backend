package com.dugnan.moqi.chapter.entitycard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.SourceRef;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.planning.PlanningModels.PlanReference;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 按显式引用、唯一名称或别名及前章状态确定性选择章节相关实体。
 */
@Component
public class GenerationEntityCardSelector {

    private static final String STATUS_ACTIVE = "active";
    private static final String SAFE_PROHIBITION = "不得补造未确认的背景、能力、关系或当前状态。";

    private final SettingEntryMapper settingMapper;
    private final ObjectMapper objectMapper;

    public GenerationEntityCardSelector(SettingEntryMapper settingMapper, ObjectMapper objectMapper) {
        this.settingMapper = settingMapper;
        this.objectMapper = objectMapper;
    }

    public Selection select(
            Long workId,
            Long chapterId,
            Integer chapterNo,
            List<ScenePlanView> scenes,
            List<String> chapterTexts,
            List<String> previousStateTexts) {
        List<Reference> references = references(scenes);
        Map<Long, SettingEntryEntity> explicit = explicitSettings(references);
        validateExplicit(workId, references, explicit);
        List<SettingEntryEntity> available = availableSettings(workId);
        Map<Long, RankedCard> selected = new LinkedHashMap<>();
        Map<String, GenerationEntityCard> safeReferences = new LinkedHashMap<>();

        references.forEach(reference -> selectReference(
                reference, available, explicit, chapterId, chapterNo, selected, safeReferences));
        selectTextMatches(chapterTexts, 2, available, chapterId, chapterNo, selected);
        selectTextMatches(previousStateTexts, 3, available, chapterId, chapterNo, selected);

        List<GenerationEntityCard> cards = new ArrayList<>();
        selected.values().stream()
                .sorted(Comparator.comparingInt(RankedCard::rank)
                        .thenComparing(item -> normalized(item.card().type()))
                        .thenComparing(item -> normalized(item.card().name()))
                        .thenComparing(item -> item.card().entityId()))
                .map(RankedCard::card)
                .forEach(cards::add);
        safeReferences.values().stream()
                .sorted(Comparator.comparing(GenerationEntityCard::type)
                        .thenComparing(GenerationEntityCard::name))
                .forEach(cards::add);
        List<SourceRef> sourceRefs = selected.values().stream()
                .map(RankedCard::card)
                .map(card -> new SourceRef("SETTING_ENTRY", String.valueOf(card.entityId()), card.sourceVersion()))
                .sorted(Comparator.comparing(SourceRef::sourceId))
                .toList();
        return new Selection(cards, sourceRefs);
    }

    private List<SettingEntryEntity> availableSettings(Long workId) {
        return settingMapper.selectList(new LambdaQueryWrapper<SettingEntryEntity>()
                        .eq(SettingEntryEntity::getWorkId, workId)
                        .eq(SettingEntryEntity::getEntryStatus, STATUS_ACTIVE)
                        .eq(SettingEntryEntity::getDeleted, 0)
                        .orderByAsc(SettingEntryEntity::getId))
                .stream()
                .filter(this::isActive)
                .toList();
    }

    private Map<Long, SettingEntryEntity> explicitSettings(List<Reference> references) {
        Set<Long> ids = references.stream().map(Reference::settingEntryId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return settingMapper.selectBatchIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(SettingEntryEntity::getId, item -> item));
    }

    private void validateExplicit(
            Long workId,
            List<Reference> references,
            Map<Long, SettingEntryEntity> explicit) {
        for (Reference reference : references) {
            if (reference.settingEntryId() == null) {
                continue;
            }
            SettingEntryEntity setting = explicit.get(reference.settingEntryId());
            if (setting == null || !workId.equals(setting.getWorkId()) || !isActive(setting)) {
                throw new BusinessException(ErrorCode.SCENE_PLAN_SOURCE_STALE,
                        "场景规划引用了无效、过期或跨作品设定：" + reference.settingEntryId());
            }
        }
    }

    private void selectReference(
            Reference reference,
            List<SettingEntryEntity> available,
            Map<Long, SettingEntryEntity> explicit,
            Long chapterId,
            Integer chapterNo,
            Map<Long, RankedCard> selected,
            Map<String, GenerationEntityCard> safeReferences) {
        if (reference.settingEntryId() != null) {
            put(selected, card(explicit.get(reference.settingEntryId()), chapterId, chapterNo), 0);
            return;
        }
        List<SettingEntryEntity> matches = available.stream()
                .filter(setting -> names(setting).contains(normalized(reference.name())))
                .toList();
        if (matches.size() == 1) {
            put(selected, card(matches.get(0), chapterId, chapterNo), 1);
            return;
        }
        String key = normalized(reference.type()) + ":" + normalized(reference.name());
        safeReferences.putIfAbsent(key, new GenerationEntityCard(
                null, reference.type(), reference.name(), List.of(), null, null, null, null, null,
                matches.isEmpty() ? SAFE_PROHIBITION : "名称或别名存在歧义；" + SAFE_PROHIBITION,
                false, null, "published-plan-name"));
    }

    private void selectTextMatches(
            List<String> texts,
            int rank,
            List<SettingEntryEntity> available,
            Long chapterId,
            Integer chapterNo,
            Map<Long, RankedCard> selected) {
        String corpus = normalized(String.join("\n", texts == null ? List.of() : texts));
        if (!StringUtils.hasText(corpus)) {
            return;
        }
        Map<String, List<SettingEntryEntity>> names = new LinkedHashMap<>();
        available.forEach(setting -> names(setting).forEach(name -> names
                .computeIfAbsent(name, ignored -> new ArrayList<>()).add(setting)));
        names.forEach((name, matches) -> {
            if (StringUtils.hasText(name) && corpus.contains(name) && matches.size() == 1) {
                put(selected, card(matches.get(0), chapterId, chapterNo), rank);
            }
        });
    }

    private void put(Map<Long, RankedCard> selected, GenerationEntityCard card, int rank) {
        selected.compute(card.entityId(), (id, current) -> current == null || rank < current.rank()
                ? new RankedCard(rank, card) : current);
    }

    private GenerationEntityCard card(SettingEntryEntity setting, Long chapterId, Integer chapterNo) {
        JsonNode attributes = jsonObject(setting.getAttributesJson());
        List<String> aliases = aliases(setting.getAliasesJson());
        boolean firstEstablished = Objects.equals(chapterId, setting.getSourceChapterId())
                || Integer.valueOf(1).equals(chapterNo) && setting.getSourceChapterId() == null;
        return new GenerationEntityCard(
                setting.getId(), setting.getSettingType(), setting.getName(), aliases,
                attribute(attributes, "affiliation", "所属"),
                attribute(attributes, "storyRole", "story_role", "故事作用"),
                attribute(attributes, "currentState", "current_state", "当前状态"),
                attribute(attributes, "characterKnowledge", "knowledgeBoundary", "角色认知边界"),
                attribute(attributes, "firstAppearanceExplanation", "first_appearance", "首次出现说明"),
                attribute(attributes, "prohibitedInference", "doNotInfer", "禁止推断"),
                firstEstablished, setting.getContent(), String.valueOf(setting.getVersion()));
    }

    private Set<String> names(SettingEntryEntity setting) {
        Set<String> names = new LinkedHashSet<>();
        addName(names, setting.getName());
        aliases(setting.getAliasesJson()).forEach(alias -> addName(names, alias));
        return names;
    }

    private List<String> aliases(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && StringUtils.hasText(item.asText())) {
                    values.add(item.asText().trim());
                }
            });
            return values.stream().distinct().sorted().toList();
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.SCENE_PLAN_SOURCE_STALE, "已确认实体的别名格式无效", exception);
        }
    }

    private JsonNode jsonObject(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.SCENE_PLAN_SOURCE_STALE, "已确认实体的属性格式无效", exception);
        }
    }

    private String attribute(JsonNode attributes, String... keys) {
        for (String key : keys) {
            JsonNode value = attributes.get(key);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private List<Reference> references(List<ScenePlanView> scenes) {
        List<Reference> references = new ArrayList<>();
        for (ScenePlanView scene : orderedScenes(scenes)) {
            ScenePlanContent content = scene.content();
            addReference(references, content.viewpointCharacter(), "人物");
            addReference(references, content.location(), "地点");
            content.participants().forEach(reference -> addReference(references, reference, "人物"));
            content.requiredSettings().forEach(reference -> addReference(references, reference, "设定"));
        }
        return references;
    }

    private void addReference(List<Reference> references, PlanReference reference, String type) {
        if (reference == null) {
            return;
        }
        if (reference.settingEntryId() == null && !StringUtils.hasText(reference.name())) {
            return;
        }
        references.add(new Reference(
                reference.settingEntryId(), type,
                StringUtils.hasText(reference.name()) ? reference.name().trim() : ""));
    }

    private List<ScenePlanView> orderedScenes(Collection<ScenePlanView> scenes) {
        return scenes == null ? List.of() : scenes.stream()
                .filter(scene -> "planned".equals(scene.content().status()))
                .sorted(Comparator.comparing(ScenePlanView::sequence).thenComparing(ScenePlanView::scenePlanId))
                .toList();
    }

    private boolean isActive(SettingEntryEntity setting) {
        return setting != null && !Integer.valueOf(1).equals(setting.getDeleted())
                && STATUS_ACTIVE.equals(setting.getEntryStatus());
    }

    private void addName(Set<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(normalized(value));
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Selection(List<GenerationEntityCard> cards, List<SourceRef> sourceRefs) {
        public Selection {
            cards = cards == null ? List.of() : List.copyOf(cards);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }

    private record Reference(Long settingEntryId, String type, String name) {
    }

    private record RankedCard(int rank, GenerationEntityCard card) {
    }
}
