package com.dugnan.moqi.planning.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.planning.SceneOutlineRevisionBridgeService;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CloneScenePlanCandidateRequest;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CreateOutlineRevisionCandidateRequest;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * @author dgn
 * @date 2026-08-12
 * @description 验证场景修订桥接接口的路径和请求绑定。
 */
class SceneOutlineRevisionBridgeControllerTest {
    private SceneOutlineRevisionBridgeService bridgeService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        bridgeService = Mockito.mock(SceneOutlineRevisionBridgeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SceneOutlineRevisionBridgeController(bridgeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bindsCloneAndOutlineCandidateRequests() throws Exception {
        mvc.perform(post("/api/chapters/65/scene-plan-candidates/from-current")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourcePlanId":5,"baseOutlineRevision":2,"idempotencyKey":"clone-1"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/chapters/65/scene-plan-candidates/9/outline-revision-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":3,"consistencyReportId":12,"conversationId":7,
                                 "confirmedBriefId":8,"baseOutlineRevision":2,"idempotencyKey":"outline-1"}
                                """))
                .andExpect(status().isOk());

        verify(bridgeService).cloneFromCurrent(
                org.mockito.ArgumentMatchers.eq(65L), any(CloneScenePlanCandidateRequest.class));
        verify(bridgeService).createOutlineCandidate(
                org.mockito.ArgumentMatchers.eq(65L), org.mockito.ArgumentMatchers.eq(9L),
                any(CreateOutlineRevisionCandidateRequest.class));
    }
}
