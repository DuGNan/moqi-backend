package com.dugnan.moqi.chapter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceModels.PreferenceDetail;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceModels.PreferenceRequest;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceService;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 验证回复策略偏好的最小 HTTP 读写契约。
 */
class ReplyPolicyPreferenceControllerTest {

    private ReplyPolicyPreferenceService preferenceService;
    private MockMvc mvc;

    /**
     * 初始化独立控制器测试环境。
     */
    @BeforeEach
    void setUp() {
        preferenceService = Mockito.mock(ReplyPolicyPreferenceService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ReplyPolicyPreferenceController(preferenceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * GET 接口返回指定作用域的当前偏好。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void getsPreference() throws Exception {
        when(preferenceService.get("chapter", 2L))
                .thenReturn(new PreferenceDetail(9L, "chapter", 2L, "balanced", 3));

        mvc.perform(get("/api/reply-policy/preferences")
                        .param("scopeType", "chapter")
                        .param("scopeId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.scopeType").value("chapter"))
                .andExpect(jsonPath("$.data.replyDepth").value("balanced"))
                .andExpect(jsonPath("$.data.version").value(3));
    }

    /**
     * PUT 接口接收 baseVersion 并返回更新后的版本。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void savesPreference() throws Exception {
        when(preferenceService.save(any(PreferenceRequest.class)))
                .thenReturn(new PreferenceDetail(9L, "conversation", 8L, "deep", 4));

        mvc.perform(put("/api/reply-policy/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scopeType":"conversation",
                                  "scopeId":8,
                                  "replyDepth":"deep",
                                  "baseVersion":3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.scopeId").value(8))
                .andExpect(jsonPath("$.data.replyDepth").value("deep"))
                .andExpect(jsonPath("$.data.version").value(4));
    }
}
