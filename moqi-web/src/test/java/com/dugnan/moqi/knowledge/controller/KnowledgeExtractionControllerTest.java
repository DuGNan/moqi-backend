package com.dugnan.moqi.knowledge.controller;

import static org.mockito.ArgumentMatchers.any;
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
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.CandidateDecision;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ConfirmCandidateRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.IgnoreCandidateRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.StartExtractionRequest;
import com.dugnan.moqi.knowledge.service.KnowledgeExtractionService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * 验证正文知识提取的 HTTP 契约和人工决策参数传递。
 */
class KnowledgeExtractionControllerTest {

    private KnowledgeExtractionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeExtractionService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeExtractionController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void startsAndRestoresExtractionBatch() throws Exception {
        BatchView batch = new BatchView(
                9L, 1L, 5L, 7L, 3L, 4L, "story-knowledge-extractor-v1",
                2, "fingerprint", "ready", 0, null, List.of(), 1, null, null);
        when(service.start(5L, 7L, new StartExtractionRequest("key-1"))).thenReturn(batch);
        when(service.latest(5L, 7L)).thenReturn(batch);

        mvc.perform(post("/api/chapters/5/generations/7/knowledge-extractions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"key-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.batchStatus").value("ready"));
        mvc.perform(get("/api/chapters/5/generations/7/knowledge-extractions/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceFingerprint").value("fingerprint"));
    }

    @Test
    void forwardsExplicitCandidateDecision() throws Exception {
        when(service.confirm(any(), any())).thenReturn(
                new CandidateDecision(11L, "confirmed", "setting", 21L, 1, null));
        when(service.ignore(any(), any())).thenReturn(
                new CandidateDecision(12L, "ignored", null, null, 1, null));

        mvc.perform(post("/api/knowledge-extraction-candidates/11/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "baseVersion": 0,
                                  "resolution": "merge",
                                  "mergeTargetId": 21
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateStatus").value("confirmed"))
                .andExpect(jsonPath("$.data.targetId").value(21));
        mvc.perform(post("/api/knowledge-extraction-candidates/12/ignore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateStatus").value("ignored"));

        verify(service).confirm(
                11L,
                new ConfirmCandidateRequest(0, "merge", 21L, null));
        verify(service).ignore(12L, new IgnoreCandidateRequest(0));
    }

    @Test
    void mapsExtractionNotFoundAndStaleToStableHttpStatuses() throws Exception {
        when(service.latest(5L, 7L)).thenThrow(
                new BusinessException(ErrorCode.KNOWLEDGE_EXTRACTION_NOT_FOUND, "批次不存在"));
        when(service.confirm(any(), any())).thenThrow(
                new BusinessException(ErrorCode.KNOWLEDGE_EXTRACTION_STALE, "来源已过期"));

        mvc.perform(get("/api/chapters/5/generations/7/knowledge-extractions/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EXTRACTION_NOT_FOUND"));
        mvc.perform(post("/api/knowledge-extraction-candidates/11/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":0,\"resolution\":\"create\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EXTRACTION_STALE"));
    }
}
