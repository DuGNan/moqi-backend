package com.dugnan.moqi.work.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterOpen;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkList;
import com.dugnan.moqi.work.service.WorkChapterService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkChapterControllerTest {
    private WorkChapterService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(WorkChapterService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WorkController(service), new ChapterController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void listsWorksInUnifiedResponse() throws Exception {
        when(service.listWorks(null, null, null)).thenReturn(new WorkList(List.of()));
        mvc.perform(get("/api/works"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.works").isArray());
    }

    @Test
    void rejectsBlankWorkTitle() throws Exception {
        mvc.perform(post("/api/works").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void rejectsMalformedJsonAsBadRequest() throws Exception {
        mvc.perform(post("/api/works").contentType(MediaType.APPLICATION_JSON).content("{title:broken}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void mapsMissingChapterTo404() throws Exception {
        when(service.getChapter(99L)).thenThrow(new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在"));
        mvc.perform(get("/api/chapters/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAPTER_NOT_FOUND"));
    }

    @Test
    void opensChapterWithoutPersistingSource() throws Exception {
        when(service.openChapter(2L)).thenReturn(new ChapterOpen(1L, 2L, "co_creation", null, null, 0, null, null, 0, null));
        mvc.perform(post("/api/chapters/2/open").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"home_recent_chapter\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultWorkspace").value("co_creation"))
                .andExpect(jsonPath("$.data.conversationId").value(nullValue()))
                .andExpect(jsonPath("$.data.latestPreviewGenerationId").value(nullValue()))
                .andExpect(jsonPath("$.data.outlineId").value(nullValue()));
    }
}
