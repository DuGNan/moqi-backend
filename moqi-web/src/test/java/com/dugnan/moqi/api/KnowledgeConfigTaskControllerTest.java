package com.dugnan.moqi.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.config.controller.ModelStatusController;
import com.dugnan.moqi.config.controller.UserConfigController;
import com.dugnan.moqi.config.dto.UserConfigModels.ModelStatus;
import com.dugnan.moqi.config.dto.UserConfigModels.UserConfigDetail;
import com.dugnan.moqi.config.dto.UserConfigModels.UserConfigSaved;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.controller.ChapterKnowledgeController;
import com.dugnan.moqi.knowledge.controller.SettingCandidateController;
import com.dugnan.moqi.knowledge.controller.WorkKnowledgeController;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ChapterKeyEventList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ChapterSummaryDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ConfirmSettingResult;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ForeshadowingDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ForeshadowingList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.IgnoreSettingResult;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingCandidateList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingList;
import com.dugnan.moqi.knowledge.service.KnowledgeService;
import com.dugnan.moqi.task.controller.AiTaskController;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskCanceled;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskDetail;
import com.dugnan.moqi.task.service.AiTaskService;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 验证 #27 全部 HTTP 路由、参数传递与统一响应结构。
 */
class KnowledgeConfigTaskControllerTest {

    private KnowledgeService knowledgeService;
    private UserConfigService userConfigService;
    private AiTaskService aiTaskService;
    private ObjectMapper objectMapper;
    private MockMvc mvc;

    /**
     * 初始化 #27 控制器测试环境。
     */
    @BeforeEach
    void setUp() {
        knowledgeService = Mockito.mock(KnowledgeService.class);
        userConfigService = Mockito.mock(UserConfigService.class);
        aiTaskService = Mockito.mock(AiTaskService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mvc = MockMvcBuilders
                .standaloneSetup(
                        new WorkKnowledgeController(knowledgeService),
                        new SettingCandidateController(knowledgeService),
                        new ChapterKnowledgeController(knowledgeService),
                        new UserConfigController(userConfigService),
                        new ModelStatusController(userConfigService),
                        new AiTaskController(aiTaskService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 验证作品知识层列表接口及候选过滤参数。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void exposesWorkKnowledgeLists() throws Exception {
        when(knowledgeService.listSettingCandidates(1L, 12L, "pending", "character", "林", 2, 25))
                .thenReturn(new SettingCandidateList(1L, List.of()));
        when(knowledgeService.listSettings(1L, "character", "active", "林", 2, 25))
                .thenReturn(new SettingList(1L, List.of()));
        when(knowledgeService.listForeshadowings(1L, "planted", 12L, null, 2, 25))
                .thenReturn(new ForeshadowingList(1L, List.of()));

        mvc.perform(get("/api/works/1/setting-candidates")
                        .param("chapterId", "12")
                        .param("candidateStatus", "pending")
                        .param("settingType", "character")
                        .param("keyword", "林")
                        .param("page", "2")
                        .param("pageSize", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates").isArray());
        mvc.perform(get("/api/works/1/settings")
                        .param("settingType", "character")
                        .param("entryStatus", "active")
                        .param("keyword", "林")
                        .param("page", "2")
                        .param("pageSize", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings").isArray());
        mvc.perform(get("/api/works/1/foreshadowings")
                        .param("status", "planted")
                        .param("sourceChapterId", "12")
                        .param("page", "2")
                        .param("pageSize", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.foreshadowings").isArray());
        verify(knowledgeService).listSettingCandidates(1L, 12L, "pending", "character", "林", 2, 25);
        verify(knowledgeService).listSettings(1L, "character", "active", "林", 2, 25);
        verify(knowledgeService).listForeshadowings(1L, "planted", 12L, null, 2, 25);
    }

    /**
     * 验证候选确认与忽略接口。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void exposesCandidateTransitions() throws Exception {
        when(knowledgeService.confirmSettingCandidate(Mockito.eq(501L), any()))
                .thenReturn(new ConfirmSettingResult(501L, "confirmed", 301L, null));
        when(knowledgeService.ignoreSettingCandidate(Mockito.eq(502L), any()))
                .thenReturn(new IgnoreSettingResult(502L, "ignored", null));

        mvc.perform(post("/api/setting-candidates/501/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingType\":\"character\",\"name\":\"林风\",\"content\":\"正文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settingId").value(301));
        mvc.perform(post("/api/setting-candidates/502/ignore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不保留\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateStatus").value("ignored"));
    }

    /**
     * 验证伏笔创建接口。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void exposesForeshadowingCreation() throws Exception {
        when(knowledgeService.createForeshadowing(Mockito.eq(1L), any()))
                .thenReturn(new ForeshadowingDetail(
                        801L, 1L, 12L, "隐藏房间", "回应追问", null, null, null,
                        "planted", null, null, null, null));

        mvc.perform(post("/api/works/1/foreshadowings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceChapterId\":12,\"title\":\"隐藏房间\","
                                + "\"description\":\"回应追问\",\"status\":\"planted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(801));
    }

    /**
     * 验证章节摘要与关键事件接口。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void exposesChapterKnowledgeReads() throws Exception {
        when(knowledgeService.getChapterSummary(12L)).thenReturn(new ChapterSummaryDetail(
                1101L,
                1L,
                12L,
                "摘要",
                objectMapper.createArrayNode(),
                objectMapper.createArrayNode(),
                objectMapper.createArrayNode(),
                objectMapper.createArrayNode(),
                "confirmed",
                8,
                null,
                null));
        when(knowledgeService.listChapterKeyEvents(12L))
                .thenReturn(new ChapterKeyEventList(1L, 12L, List.of()));

        mvc.perform(get("/api/chapters/12/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("摘要"));
        mvc.perform(get("/api/chapters/12/key-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events").isArray());
    }

    /**
     * 验证全局忽略空值时，空章节摘要仍保留统一响应的 data 字段。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void keepsNullDataForMissingChapterSummary() throws Exception {
        when(knowledgeService.getChapterSummary(12L)).thenReturn(null);

        mvc.perform(get("/api/chapters/12/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    /**
     * 验证用户配置读取与保存接口。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void exposesUserConfigReadAndWrite() throws Exception {
        when(userConfigService.getConfig("appearance.preferences"))
                .thenReturn(new UserConfigDetail(
                        null,
                        "local-user",
                        "appearance.preferences",
                        objectMapper.createObjectNode(),
                        0,
                        null));
        when(userConfigService.updateConfig(Mockito.eq("appearance.preferences"), any()))
                .thenReturn(new UserConfigSaved(601L, "appearance.preferences", 0, null));

        mvc.perform(get("/api/user-configs/appearance.preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.configValue").isMap());
        mvc.perform(put("/api/user-configs/appearance.preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":0,\"configValue\":{\"theme\":\"light\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(601));
    }

    /**
     * 验证模型状态接口固定返回离线可用性结论。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void exposesOfflineModelStatus() throws Exception {
        when(userConfigService.getModelStatus()).thenReturn(new ModelStatus(
                true,
                false,
                "openai_compatible",
                "OpenAI Compatible",
                "gpt-4.1",
                "not_tested",
                null,
                null));

        mvc.perform(get("/api/system/model-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.lastTestStatus").value("not_tested"));
    }

    /**
     * 验证模型连接测试端点传递配置版本并返回持久化后的状态。
     */
    @Test
    void exposesModelConnectionTest() throws Exception {
        when(userConfigService.testModelConnection(3)).thenReturn(new ModelStatus(
                true,
                true,
                "deepseek",
                "DeepSeek",
                "deepseek-v4-flash",
                "success",
                null,
                java.time.LocalDateTime.of(2026, 7, 22, 1, 0),
                4));

        mvc.perform(post("/api/system/model-status/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.lastTestStatus").value("success"))
                .andExpect(jsonPath("$.data.configVersion").value(4));
    }

    /**
     * 验证 AI 任务查询与取消接口。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void exposesAiTaskReadAndCancel() throws Exception {
        when(aiTaskService.getTask(9001L)).thenReturn(new AiTaskDetail(
                9001L, "conversation_reply", "running", 1L, 12L,
                null, null, null, null, null, null));
        when(aiTaskService.cancelTask(9001L))
                .thenReturn(new AiTaskCanceled(9001L, "canceled", null));

        mvc.perform(get("/api/ai-tasks/9001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("running"));
        mvc.perform(post("/api/ai-tasks/9001/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("canceled"));
    }

    /**
     * 验证敏感配置、版本冲突和任务不存在映射为明确 HTTP 状态。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void mapsIssue27BusinessErrorsToHttpStatuses() throws Exception {
        when(userConfigService.updateConfig(Mockito.eq("model.active"), any()))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "配置包含敏感键"));
        when(userConfigService.updateConfig(Mockito.eq("writing.preferences"), any()))
                .thenThrow(new BusinessException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置已更新"));
        when(aiTaskService.getTask(999L))
                .thenThrow(new BusinessException(ErrorCode.AI_TASK_NOT_FOUND, "AI 任务不存在"));

        mvc.perform(put("/api/user-configs/model.active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":0,\"configValue\":{\"apiKey\":\"plain\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(put("/api/user-configs/writing.preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":1,\"configValue\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
        mvc.perform(get("/api/ai-tasks/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_TASK_NOT_FOUND"));
    }

    /**
     * 验证分页参数类型错误和整数溢出统一映射为 400。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void mapsInvalidPaginationTypesToBadRequest() throws Exception {
        mvc.perform(get("/api/works/1/settings").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/works/1/setting-candidates").param("pageSize", "2147483648"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /**
     * 验证候选状态竞争和 AI 任务取消竞争映射为 409。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void mapsIssue27StateConflictsToConflict() throws Exception {
        when(knowledgeService.confirmSettingCandidate(Mockito.eq(501L), any()))
                .thenThrow(new BusinessException(
                        ErrorCode.valueOf("SETTING_CANDIDATE_CONFLICT"),
                        "候选状态已变化"));
        when(aiTaskService.cancelTask(9001L))
                .thenThrow(new BusinessException(
                        ErrorCode.valueOf("AI_TASK_STATE_CONFLICT"),
                        "任务状态已变化"));

        mvc.perform(post("/api/setting-candidates/501/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingType\":\"character\",\"name\":\"林风\",\"content\":\"正文\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_CANDIDATE_CONFLICT"));
        mvc.perform(post("/api/ai-tasks/9001/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_TASK_STATE_CONFLICT"));
    }
}
