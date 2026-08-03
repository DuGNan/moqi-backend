package com.dugnan.moqi.chapter.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.ReaderProgress;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.StateChange;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefState;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefView;
import com.dugnan.moqi.chapter.service.ChapterConsensusService;
import com.dugnan.moqi.chapter.service.ChapterConsensusTaskService;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConsensusTaskCreated;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ResolveDecisionRequest;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证章节结构化共识接口和稳定错误状态。
 */
class ChapterConsensusControllerTest {

    private ChapterConsensusService consensusService;
    private ChapterConsensusTaskService consensusTaskService;

    private MockMvc mvc;

    /**
     * 初始化章节共识接口测试。
     */
    @BeforeEach
    void setUp() {
        consensusService = Mockito.mock(ChapterConsensusService.class);
        consensusTaskService = Mockito.mock(ChapterConsensusTaskService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ChapterConsensusController(consensusService, consensusTaskService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 验证状态接口同时返回最新草稿和已确认版本。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void getsBriefState() throws Exception {
        when(consensusService.getState(2L))
                .thenReturn(new BriefState(view(21L, "draft", 0), view(20L, "confirmed", 1)));

        mvc.perform(get("/api/chapters/2/briefs/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestDraft.id").value(21))
                .andExpect(jsonPath("$.data.latestConfirmed.id").value(20))
                .andExpect(jsonPath("$.data.latestDraft.contentFormat").value("structured_v1"));
    }

    /**
     * 验证结构化请求可以追加草稿。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void createsBriefDraft() throws Exception {
        when(consensusService.createDraft(Mockito.eq(2L), Mockito.any()))
                .thenReturn(view(21L, "draft", 0));

        mvc.perform(post("/api/chapters/2/briefs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "consensus": {
                                    "schemaVersion": 1,
                                    "chapterTask": "推进选择",
                                    "stateChange": {"from": "犹豫", "to": "决断"},
                                    "keyPush": "承担代价",
                                    "readerProgress": {"payoff": "兑现", "openQuestion": "谁泄密"},
                                    "writingBoundaries": [],
                                    "decisions": []
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(21))
                .andExpect(jsonPath("$.data.briefStatus").value("draft"));
    }

    /**
     * 验证 Brief 版本冲突映射为 HTTP 409。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void mapsBriefVersionConflictTo409() throws Exception {
        when(consensusService.confirm(Mockito.eq(2L), Mockito.eq(21L), Mockito.any()))
                .thenThrow(new BusinessException(
                        ErrorCode.CHAPTER_BRIEF_VERSION_CONFLICT,
                        "Brief 已更新"));

        mvc.perform(post("/api/chapters/2/briefs/21/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAPTER_BRIEF_VERSION_CONFLICT"));
    }

    /**
     * 验证共识收束任务接口返回 queued 任务引用。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void createsConsensusTask() throws Exception {
        when(consensusTaskService.createTask(Mockito.eq(2L), Mockito.any()))
                .thenReturn(new ConsensusTaskCreated(31L, "queued", 2L));

        mvc.perform(post("/api/chapters/2/briefs/consensus-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":8,\"baseBriefId\":21}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(31))
                .andExpect(jsonPath("$.data.taskStatus").value("queued"));
    }

    /** 验证候选处理路径、请求体和服务委派保持一致。 */
    @Test
    void resolvesDecisionCandidate() throws Exception {
        when(consensusService.resolveDecision(Mockito.eq(2L), Mockito.eq(21L), Mockito.eq("protagonist_choice"), Mockito.any()))
                .thenReturn(view(22L, "draft", 0));

        mvc.perform(post("/api/chapters/2/briefs/21/decisions/protagonist_choice/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":3,\"action\":\"reject\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(22));

        ArgumentCaptor<ResolveDecisionRequest> requestCaptor = ArgumentCaptor.forClass(ResolveDecisionRequest.class);
        Mockito.verify(consensusService).resolveDecision(
                Mockito.eq(2L), Mockito.eq(21L), Mockito.eq("protagonist_choice"), requestCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().baseVersion()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().action()).isEqualTo("reject");
    }

    /**
     * 构造测试 Brief 视图。
     *
     * @param id Brief ID
     * @param status Brief 状态
     * @param version Brief 版本
     * @return Brief 视图
     */
    private BriefView view(Long id, String status, Integer version) {
        ChapterConsensusContentV1 consensus = new ChapterConsensusContentV1(
                1,
                "推进选择",
                new StateChange("犹豫", "决断"),
                "承担代价",
                new ReaderProgress("兑现", "谁泄密"),
                List.of(),
                List.of());
        return new BriefView(
                id,
                1L,
                2L,
                status,
                version,
                "structured_v1",
                consensus,
                null,
                null,
                null);
    }
}
