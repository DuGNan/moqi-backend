package com.dugnan.moqi.chapter.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.BriefDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageCreated;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageList;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineRequest;
import com.dugnan.moqi.chapter.service.ChapterCollaborationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * @author dgn
 * @date 2026-07-16
 * @description 验证章节共创接口的 HTTP 协议与错误状态。
 */
class ChapterCollaborationControllerTest {

    private ChapterCollaborationService service;
    private MockMvc mvc;

    /**
     * 初始化章节共创控制器测试环境。
     */
    @BeforeEach
    void setUp() {
        service = Mockito.mock(ChapterCollaborationService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(
                        new ChapterCollaborationController(service),
                        new ConversationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 验证章节会话接口返回统一响应。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void getsChapterConversation() throws Exception {
        when(service.getConversation(2L))
                .thenReturn(new ConversationDetail(8L, 1L, 2L, "chapter_co_creation", "active", null, null));

        mvc.perform(get("/api/chapters/2/conversation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(8));
    }

    /**
     * 验证发送会话消息接口支持创建 AI 任务。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void sendsConversationMessageWithAiTask() throws Exception {
        when(service.sendMessage(Mockito.eq(8L), Mockito.any()))
                .thenReturn(new MessageCreated(11L, 8L, 2L, "user", "讨论目标", 12L, null, null));

        mvc.perform(post("/api/conversations/8/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageRole\":\"user\",\"content\":\"讨论目标\",\"createAiTask\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.aiTaskId").value(12));
    }

    /**
     * 验证消息列表接口返回数组包装。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void listsConversationMessages() throws Exception {
        when(service.listMessages(8L)).thenReturn(new MessageList(List.of()));

        mvc.perform(get("/api/conversations/8/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").isArray());
    }

    /**
     * 验证 latest brief 接口可以保存并返回内容。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void savesLatestBrief() throws Exception {
        when(service.saveLatestBrief(Mockito.eq(2L), Mockito.any()))
                .thenReturn(new BriefDetail(5L, 1L, 2L, "draft", "本章 brief", null, null));

        mvc.perform(put("/api/chapters/2/briefs/latest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefContent\":\"本章 brief\",\"briefStatus\":\"draft\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.briefContent").value("本章 brief"));
    }

    /**
     * 验证大纲保存 revision 冲突会返回 409。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void mapsOutlineRevisionConflictTo409() throws Exception {
        when(service.saveOutline(Mockito.eq(2L), Mockito.any(OutlineRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.OUTLINE_REVISION_CONFLICT, "大纲已被更新"));

        mvc.perform(put("/api/chapters/2/outline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outlineContent\":\"{}\",\"outlineStatus\":\"draft\",\"baseRevision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTLINE_REVISION_CONFLICT"));
    }

    /**
     * 验证刷新大纲接口返回新的 revision。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void refreshesOutline() throws Exception {
        when(service.refreshOutline(2L))
                .thenReturn(new OutlineDetail(6L, 1L, 2L, "draft", "{}", 2, null, null));

        mvc.perform(post("/api/chapters/2/outline/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2));
    }
}
