package com.dugnan.moqi.chapter.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;
import com.dugnan.moqi.chapter.service.ProseObjectConversationService;

/** 验证正文对象会话读取与幂等创建路由。 */
class ProseObjectConversationControllerTest {

    private ProseObjectConversationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ProseObjectConversationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProseObjectConversationController(service)).build();
    }

    @Test
    void returnsEmptyUntilObjectConversationIsCreated() throws Exception {
        mvc.perform(get("/api/chapters/2/prose-objects/formal:2/conversation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void createsConversationBoundToRequestedObject() throws Exception {
        when(service.createOrGet(2L, "candidate:8")).thenReturn(
                new ConversationDetail(11L, 1L, 2L, "prose_object", "active", null, null, "candidate:8"));

        mvc.perform(post("/api/chapters/2/prose-objects/candidate:8/conversation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.targetObjectId").value("candidate:8"));
    }
}
