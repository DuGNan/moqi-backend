package com.dugnan.moqi.chapter.entitycard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * @description 验证实体卡只选择同作品的已确认知识并安全处理名称歧义。
 */
class GenerationEntityCardSelectorTest {

    private final SettingEntryMapper settingMapper = mock(SettingEntryMapper.class);
    private GenerationEntityCardSelector selector;

    @BeforeEach
    void setUp() {
        selector = new GenerationEntityCardSelector(settingMapper, new ObjectMapper());
    }

    @Test
    void selectsExplicitIdAndRendersCompleteConfirmedFields() {
        SettingEntryEntity character = setting(101L, 2L, "character", "林风", "[\"阿风\"]", 12L);
        character.setContent("舰桥值班员");
        character.setAttributesJson("""
                {"affiliation":"长夜号","storyRole":"视角人物","currentState":"受伤",\
                 "characterKnowledge":"不知道内鬼身份","firstAppearanceExplanation":"先交代值班职责",\
                 "prohibitedInference":"不得补造军衔"}
                """);
        when(settingMapper.selectBatchIds(any())).thenReturn(List.of(character));
        when(settingMapper.selectList(any())).thenReturn(List.of(character));

        var result = selector.select(2L, 12L, 1, List.of(scene(new PlanReference(101L, "阿风"))),
                List.of(), List.of());

        assertThat(result.cards()).singleElement().satisfies(card -> {
            assertThat(card.entityId()).isEqualTo(101L);
            assertThat(card.aliases()).containsExactly("阿风");
            assertThat(card.affiliation()).isEqualTo("长夜号");
            assertThat(card.storyRole()).isEqualTo("视角人物");
            assertThat(card.currentState()).isEqualTo("受伤");
            assertThat(card.characterKnowledge()).isEqualTo("不知道内鬼身份");
            assertThat(card.firstAppearanceExplanation()).isEqualTo("先交代值班职责");
            assertThat(card.prohibitedInference()).isEqualTo("不得补造军衔");
            assertThat(card.firstEstablishedInChapter()).isTrue();
            assertThat(card.sourceVersion()).isEqualTo("3");
        });
        assertThat(result.sourceRefs()).singleElement()
                .extracting("sourceType", "sourceId", "contentVersion")
                .containsExactly("SETTING_ENTRY", "101", "3");
    }

    @Test
    void selectsUniqueAliasAndPreviousConfirmedStateButNotUnrelatedEntries() {
        SettingEntryEntity character = setting(101L, 2L, "character", "林风", "[\"阿风\"]", 8L);
        SettingEntryEntity ship = setting(102L, 2L, "location", "长夜号", "[]", 8L);
        SettingEntryEntity unrelated = setting(103L, 2L, "rule", "跃迁规则", "[]", 8L);
        when(settingMapper.selectList(any())).thenReturn(List.of(character, ship, unrelated));

        var result = selector.select(2L, 12L, 2, List.of(scene(new PlanReference(null, "阿风"))),
                List.of(), List.of("林风返回长夜号"));

        assertThat(result.cards()).extracting(GenerationEntityCard::entityId).containsExactly(101L, 102L);
    }

    @Test
    void keepsAnAmbiguousNameAsAPlanOnlyCardWithoutSelectingAuthority() {
        SettingEntryEntity first = setting(101L, 2L, "character", "白露", "[]", 8L);
        SettingEntryEntity second = setting(102L, 2L, "location", "白露", "[]", 8L);
        when(settingMapper.selectList(any())).thenReturn(List.of(first, second));

        var result = selector.select(2L, 12L, 2, List.of(scene(new PlanReference(null, "白露"))),
                List.of("白露出现"), List.of());

        assertThat(result.cards()).singleElement().satisfies(card -> {
            assertThat(card.entityId()).isNull();
            assertThat(card.prohibitedInference()).contains("歧义", "不得补造");
        });
        assertThat(result.sourceRefs()).isEmpty();
    }

    @Test
    void rejectsExplicitInactiveOrCrossWorkReferences() {
        SettingEntryEntity crossWork = setting(101L, 99L, "character", "林风", "[]", 8L);
        when(settingMapper.selectBatchIds(any())).thenReturn(List.of(crossWork));

        assertThatThrownBy(() -> selector.select(
                2L, 12L, 2, List.of(scene(new PlanReference(101L, "林风"))), List.of(), List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SCENE_PLAN_SOURCE_STALE))
                .hasMessageContaining("跨作品设定");
    }

    @Test
    void excludesInactiveNameMatchesAndAcceptsAnExplicitIdWithoutAPlanName() {
        SettingEntryEntity inactive = setting(101L, 2L, "character", "旧林风", "[]", 8L);
        inactive.setEntryStatus("deprecated");
        SettingEntryEntity active = setting(102L, 2L, "character", "林风", "[]", 8L);
        when(settingMapper.selectBatchIds(any())).thenReturn(List.of(active));
        when(settingMapper.selectList(any())).thenReturn(List.of(inactive, active));

        var result = selector.select(2L, 12L, 2, List.of(scene(new PlanReference(102L, ""))),
                List.of("旧林风没有出场"), List.of());

        assertThat(result.cards()).extracting(GenerationEntityCard::entityId).containsExactly(102L);
    }

    private ScenePlanView scene(PlanReference viewpoint) {
        ScenePlanContent content = new ScenePlanContent(
                "scene-1", 1, "警报", viewpoint, "深夜", null, "夺回控制", "舱门锁死", "紧张", "快",
                List.of(), List.of(), List.of(), "取得控制", "planned", List.of(), List.of(), List.of(), "",
                List.of(), List.of(), "core", List.of(), List.of());
        return new ScenePlanView(61L, "scene-1", 1, 2, content);
    }

    private SettingEntryEntity setting(
            Long id,
            Long workId,
            String type,
            String name,
            String aliases,
            Long sourceChapterId) {
        SettingEntryEntity setting = new SettingEntryEntity();
        setting.setId(id);
        setting.setWorkId(workId);
        setting.setSettingType(type);
        setting.setName(name);
        setting.setAliasesJson(aliases);
        setting.setAttributesJson("{}");
        setting.setContent(name + "的已确认说明");
        setting.setSourceChapterId(sourceChapterId);
        setting.setEntryStatus("active");
        setting.setVersion(3);
        setting.setDeleted(0);
        return setting;
    }
}
