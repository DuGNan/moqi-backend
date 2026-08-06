package com.dugnan.moqi.work.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterOpen;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkList;
import com.dugnan.moqi.work.dto.UpdateChapterCommand;
import com.dugnan.moqi.work.dto.UpdateWorkCommand;
import com.dugnan.moqi.work.service.WorkChapterService;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:验证作品与章节接口的请求校验及响应协议。
 */
class WorkChapterControllerTest {
    private WorkChapterService workChapterService;
    private MockMvc mvc;

    /**
     * 初始化作品章节控制器测试环境。
     */
    @BeforeEach
    void setUp() {
        workChapterService = Mockito.mock(WorkChapterService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new WorkController(workChapterService), new ChapterController(workChapterService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 验证作品列表接口返回统一响应。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void listsWorksInUnifiedResponse() throws Exception {
        when(workChapterService.listWorks(null, null, null)).thenReturn(new WorkList(List.of()));
        mvc.perform(get("/api/works"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.works").isArray());
    }

    /**
     * 验证作品标题为空时返回参数校验错误。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void rejectsBlankWorkTitle() throws Exception {
        mvc.perform(post("/api/works")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /**
     * 验证请求体 JSON 格式错误时返回 400。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void rejectsMalformedJsonAsBadRequest() throws Exception {
        mvc.perform(post("/api/works")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{title:broken}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /**
     * 验证不存在章节时返回 404。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void mapsMissingChapterTo404() throws Exception {
        when(workChapterService.getChapter(99L))
                .thenThrow(new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在"));
        mvc.perform(get("/api/chapters/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAPTER_NOT_FOUND"));
    }

    /**
     * 验证打开章节请求不会持久化请求来源。
     *
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void opensChapterWithoutPersistingSource() throws Exception {
        ChapterOpen response = new ChapterOpen(
                1L, 2L, "co_creation", null, null, 0, null, null, 0, null);
        when(workChapterService.openChapter(2L)).thenReturn(response);
        mvc.perform(post("/api/chapters/2/open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"home_recent_chapter\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultWorkspace").value("co_creation"))
                .andExpect(jsonPath("$.data.conversationId").value(nullValue()))
                .andExpect(jsonPath("$.data.latestPreviewGenerationId").value(nullValue()))
                .andExpect(jsonPath("$.data.outlineId").value(nullValue()));
    }

    @Test
    void updatesWorkWithTitleAndBaseVersion() throws Exception {
        mvc.perform(put("/api/works/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新书名\",\"baseVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(workChapterService).updateWork(1L, new UpdateWorkCommand("新书名", 2));
    }

    @Test
    void rejectsNegativeChapterBaseVersion() throws Exception {
        mvc.perform(put("/api/chapters/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新章节\",\"baseVersion\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void deletesChapterWithBaseVersion() throws Exception {
        mvc.perform(delete("/api/chapters/2").param("baseVersion", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(workChapterService).deleteChapter(2L, 3);
    }
}
