package com.dugnan.moqi.knowledge.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.BatchView;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.RetryExtractionRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.StartExtractionRequest;
import com.dugnan.moqi.knowledge.service.KnowledgeExtractionService;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/** 验证正文 revision 知识提取的 HTTP 创建与恢复契约。 */
class RevisionKnowledgeExtractionControllerTest {

    private KnowledgeExtractionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeExtractionService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(new RevisionKnowledgeExtractionController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void startsAndRestoresRevisionExtractionBatch() throws Exception {
        BatchView batch = new BatchView(
                9L, 1L, 5L, 7L, 10L, 8L, 3L, 4L,
                "story-knowledge-extractor-v1", 2, "fingerprint", "ready",
                0, null, List.of(), 1, null, null);
        when(service.startRevision(1L, 5L, 10L, new StartExtractionRequest("key-1")))
                .thenReturn(batch);
        when(service.latestRevision(1L, 5L, 10L)).thenReturn(batch);

        mvc.perform(post("/api/works/1/story-revisions/chapters/5/revisions/10/knowledge-extractions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"key-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceProseRevisionId").value(10))
                .andExpect(jsonPath("$.data.sourceStoryReleaseId").value(8));
        mvc.perform(get("/api/works/1/story-revisions/chapters/5/revisions/10/knowledge-extractions/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchStatus").value("ready"));

        verify(service).startRevision(1L, 5L, 10L, new StartExtractionRequest("key-1"));
        verify(service).latestRevision(1L, 5L, 10L);
    }

    @Test
    void routesDetailRetryAndCancelWithinRevisionScope() throws Exception {
        when(service.getRevision(1L, 5L, 10L, 9L)).thenReturn(new BatchView(
                9L, 1L, 5L, 7L, 10L, 8L, 3L, 4L,
                "story-knowledge-extractor-v1", 2, "fingerprint", "failed",
                0, null, List.of(), 1, null, null));

        mvc.perform(get("/api/works/1/story-revisions/chapters/5/revisions/10/knowledge-extractions/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9));
        mvc.perform(post("/api/works/1/story-revisions/chapters/5/revisions/10/knowledge-extractions/9/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAttempt\":2}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/works/1/story-revisions/chapters/5/revisions/10/knowledge-extractions/9/cancel"))
                .andExpect(status().isOk());

        verify(service).getRevision(1L, 5L, 10L, 9L);
        verify(service).retryRevision(1L, 5L, 10L, 9L, new RetryExtractionRequest(2));
        verify(service).cancelRevision(1L, 5L, 10L, 9L);
    }
}
