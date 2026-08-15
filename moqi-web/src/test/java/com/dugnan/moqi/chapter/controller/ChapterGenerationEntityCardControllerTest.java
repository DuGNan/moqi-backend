package com.dugnan.moqi.chapter.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefSourceRef;
import com.dugnan.moqi.chapter.dto.ChapterGenerationEntityCardModels.EntityCardPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationEntityCardModels.EntityCardView;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证章节生成实体卡只读预览接口的 HTTP 契约。
 */
class ChapterGenerationEntityCardControllerTest {

    @Test
    void previewsCardsForASpecificPublishedPlan() throws Exception {
        ChapterGenerationBriefService service = mock(ChapterGenerationBriefService.class);
        when(service.previewEntityCards(12L, 6)).thenReturn(new EntityCardPreview(
                2L, 12L, 41L, 6, "chapter-generation-entity-cards-v1", "current",
                List.of(new GenerationBriefSourceRef("SETTING_ENTRY", "101", "3")), "card-hash",
                LocalDateTime.of(2026, 8, 15, 1, 0),
                List.of(new EntityCardView(
                        101L, "character", "林风", List.of("阿风"), "长夜号", "视角人物", "受伤",
                        "不知道内鬼身份", "先交代值班职责", "不得补造军衔", true, "舰桥值班员", "3"))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChapterGenerationEntityCardController(service)).build();

        mvc.perform(get("/api/chapters/12/generation-entity-cards-preview").param("scenePlanNo", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chapterPlanVersionId").value(41))
                .andExpect(jsonPath("$.data.scenePlanNo").value(6))
                .andExpect(jsonPath("$.data.templateVersion").value("chapter-generation-entity-cards-v1"))
                .andExpect(jsonPath("$.data.fingerprint").value("card-hash"))
                .andExpect(jsonPath("$.data.cards[0].entityId").value(101))
                .andExpect(jsonPath("$.data.cards[0].aliases[0]").value("阿风"))
                .andExpect(jsonPath("$.data.cards[0].firstEstablishedInChapter").value(true));
    }
}
