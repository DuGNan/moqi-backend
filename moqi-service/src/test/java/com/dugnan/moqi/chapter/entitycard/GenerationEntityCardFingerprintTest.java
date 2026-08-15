package com.dugnan.moqi.chapter.entitycard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.SourceRef;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证实体卡规范化输入和来源版本生成稳定指纹。
 */
class GenerationEntityCardFingerprintTest {

    private final GenerationEntityCardFingerprint fingerprint =
            new GenerationEntityCardFingerprint(new ObjectMapper());

    @Test
    void staysStableForTheSameCardsAndChangesWithContentOrSourceVersion() {
        GenerationEntityCard card = card("受伤", "3");
        List<SourceRef> refs = List.of(new SourceRef("SETTING_ENTRY", "101", "3"));

        String first = fingerprint.calculate("entity-card-v1", List.of(card), refs);
        String repeated = fingerprint.calculate("entity-card-v1", List.of(card), refs);
        String contentChanged = fingerprint.calculate("entity-card-v1", List.of(card("清醒", "3")), refs);
        String versionChanged = fingerprint.calculate("entity-card-v1", List.of(card("受伤", "4")),
                List.of(new SourceRef("SETTING_ENTRY", "101", "4")));

        assertThat(repeated).isEqualTo(first);
        assertThat(contentChanged).isNotEqualTo(first);
        assertThat(versionChanged).isNotEqualTo(first);
    }

    private GenerationEntityCard card(String state, String version) {
        return new GenerationEntityCard(
                101L, "character", "林风", List.of("阿风"), "长夜号", "视角人物", state,
                "不知道内鬼身份", "先交代值班职责", "不得补造军衔", true, "舰桥值班员", version);
    }
}
