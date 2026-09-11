package com.dugnan.moqi.knowledge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ExtractionOutput;
import com.dugnan.moqi.knowledge.service.impl.KnowledgeExtractionServiceImpl;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;

/**
 * 验证知识提取工作流只消费结构化 Provider 输出。
 */
class KnowledgeExtractionWorkflowDefinitionTest {

    @Test
    void extractsStructuredCandidatesWithFakeProvider() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeExtractionServiceImpl service = mock(KnowledgeExtractionServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.sourceContent(9L)).thenReturn("夜雨停了。");
        when(service.sourceFingerprint(9L)).thenReturn("fingerprint");
        when(provider.generate(any())).thenReturn(new LlmResponse(
                null,
                objectMapper.readTree("""
                        {
                          "schemaVersion": 1,
                          "candidates": [{
                            "candidateKey": "summary-1",
                            "candidateType": "chapter_summary",
                            "payload": {"summary": "夜雨停了。", "characterChanges": [], "openQuestions": []},
                            "evidence": {"startOffset": 0, "endOffset": 5, "text": "夜雨停了。"}
                          }]
                        }
                        """),
                null));
        KnowledgeExtractionWorkflowDefinition workflow = new KnowledgeExtractionWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        AgentStepResult result = workflow.execute("extract", context());

        assertThat(result.nextStepKey()).isEqualTo("validate");
        assertThat(result.checkpointState().get("output")).isInstanceOf(ExtractionOutput.class);
        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(provider).generate(request.capture());
        assertThat(request.getValue().messages().get(0).content())
                .contains("key_event={title,content,eventType,occurredOrder")
                .contains("setting={settingType,name,content}")
                .contains("Java UTF-16");
    }

    @Test
    void rejectsMalformedProviderStructure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeExtractionServiceImpl service = mock(KnowledgeExtractionServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.sourceContent(9L)).thenReturn("夜雨停了。");
        when(service.sourceFingerprint(9L)).thenReturn("fingerprint");
        when(provider.generate(any())).thenReturn(
                new LlmResponse(null, objectMapper.readTree("{\"summary\":\"非法结构\"}"), null));
        KnowledgeExtractionWorkflowDefinition workflow = new KnowledgeExtractionWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        assertThatThrownBy(() -> workflow.execute("extract", context()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCoercibleNumbersAndUnknownFieldsBeforeDeserialization() throws Exception {
        for (String json : java.util.List.of(
                "{\"schemaVersion\":\"1\",\"candidates\":[]}",
                "{\"schemaVersion\":1,\"candidates\":[{\"candidateKey\":\"s\","
                        + "\"candidateType\":\"chapter_summary\",\"payload\":{},"
                        + "\"evidence\":{\"startOffset\":0.5,\"endOffset\":1,\"text\":\"夜\"}}]}",
                "{\"schemaVersion\":1,\"candidates\":[],\"unexpected\":true}")) {
            assertMalformed(json);
        }
    }

    private void assertMalformed(String json) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeExtractionServiceImpl service = mock(KnowledgeExtractionServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.sourceContent(9L)).thenReturn("夜雨停了。");
        when(service.sourceFingerprint(9L)).thenReturn("fingerprint");
        when(provider.generate(any())).thenReturn(
                new LlmResponse(null, objectMapper.readTree(json), null));
        KnowledgeExtractionWorkflowDefinition workflow = new KnowledgeExtractionWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        assertThatThrownBy(() -> workflow.execute("extract", context()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(
                1L,
                2L,
                "extract",
                1,
                "effect",
                Map.of(
                        "batchId", 9L,
                        "workId", 1L,
                        "chapterId", 5L,
                        "aiTaskId", 3L),
                Map.of(),
                Map.of(),
                null);
    }
}
