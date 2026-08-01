package com.dugnan.moqi.agent.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;

/**
 * Agent Run 查询接口测试。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 验证断线重连可通过 HTTP 读取 Agent Run 的持久化事实。
 */
@ExtendWith(MockitoExtension.class)
class AgentRuntimeControllerTest {

    @Mock
    private AgentRuntime agentRuntime;

    private MockMvc mockMvc;

    /**
     * 初始化独立的控制器测试环境。
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeController(agentRuntime)).build();
    }

    /**
     * 等待人工确认的 Run 应暴露恢复所需的引用和版本，不返回令牌明文。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void returnsPersistedRunViewForReconnect() throws Exception {
        AgentRunView view = new AgentRunView(
                43L,
                "chapter-draft",
                "waiting_for_human",
                12L,
                35L,
                99L,
                "review",
                7L,
                88L,
                2,
                LocalDateTime.of(2026, 8, 1, 18, 0),
                null,
                null);
        given(agentRuntime.load(43L, "local-user")).willReturn(view);

        mockMvc.perform(get("/api/agent-runs/43"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.runStatus").value("waiting_for_human"))
                .andExpect(jsonPath("$.data.checkpointSequence").value(7))
                .andExpect(jsonPath("$.data.interruptionId").value(88))
                .andExpect(jsonPath("$.data.interruptionTokenVersion").value(2))
                .andExpect(jsonPath("$.data.resumeToken").doesNotExist());
    }
}
