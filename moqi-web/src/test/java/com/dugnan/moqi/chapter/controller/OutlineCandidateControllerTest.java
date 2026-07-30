package com.dugnan.moqi.chapter.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.CreateOutlineCandidateRequest;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateCreated;
import com.dugnan.moqi.chapter.service.OutlineCandidateService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 验证大纲调整候选接口与旧刷新入口的协议映射。
 */
class OutlineCandidateControllerTest {

    private OutlineCandidateService candidateService;
    private MockMvc mvc;

    /**
     * 初始化控制器测试环境。
     */
    @BeforeEach
    void setUp() {
        candidateService = Mockito.mock(OutlineCandidateService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OutlineCandidateController(candidateService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 验证规范入口创建候选而只返回资源引用。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void createsOutlineCandidate() throws Exception {
        when(candidateService.create(eq(2L), Mockito.any(CreateOutlineCandidateRequest.class)))
                .thenReturn(created());

        mvc.perform(post("/api/chapters/2/outline/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":3,"confirmedBriefId":4,"baseOutlineRevision":5,"instruction":"强化冲突"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(7))
                .andExpect(jsonPath("$.data.aiTaskId").value(8))
                .andExpect(jsonPath("$.data.taskStatus").value("queued"));
    }

    /**
     * 验证旧刷新入口映射至同一候选创建服务，不再调用正式大纲写入路径。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void mapsLegacyRefreshToCandidateCreation() throws Exception {
        when(candidateService.create(eq(2L), Mockito.any(CreateOutlineCandidateRequest.class)))
                .thenReturn(created());

        mvc.perform(post("/api/chapters/2/outline/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":3,"briefId":4,"baseRevision":5,"instruction":"强化冲突"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(7));

        ArgumentCaptor<CreateOutlineCandidateRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateOutlineCandidateRequest.class);
        verify(candidateService).create(eq(2L), requestCaptor.capture());
        CreateOutlineCandidateRequest request = requestCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(request.confirmedBriefId()).isEqualTo(4L);
        org.assertj.core.api.Assertions.assertThat(request.baseOutlineRevision()).isEqualTo(5);
    }

    /**
     * 验证 latest 路由可独立查询最新候选。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void queriesLatestCandidate() throws Exception {
        mvc.perform(get("/api/chapters/2/outline/candidates/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(candidateService).getLatest(2L);
    }

    /**
     * 验证候选状态竞争被转换为 HTTP 409。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void mapsCandidateStateConflictTo409() throws Exception {
        when(candidateService.abandon(2L, 7L))
                .thenThrow(new BusinessException(ErrorCode.OUTLINE_CANDIDATE_STATE_CONFLICT, "状态已变化"));

        mvc.perform(post("/api/chapters/2/outline/candidates/7/abandon"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTLINE_CANDIDATE_STATE_CONFLICT"));
    }

    private OutlineCandidateCreated created() {
        return new OutlineCandidateCreated(2L, 6L, 5, 7L, 8L, "queued");
    }
}
