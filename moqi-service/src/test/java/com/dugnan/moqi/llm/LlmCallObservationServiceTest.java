package com.dugnan.moqi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.entity.LlmModelCallEntity;
import com.dugnan.moqi.chapter.mapper.LlmModelCallMapper;
import com.dugnan.moqi.llm.entity.LlmModelPriceEntity;
import com.dugnan.moqi.llm.mapper.LlmModelPriceMapper;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 验证模型调用尝试编号、敏感信息白名单和版本化估算成本。
 */
@ExtendWith(MockitoExtension.class)
class LlmCallObservationServiceTest {

    @Mock
    private LlmModelCallMapper callMapper;

    @Mock
    private LlmModelPriceMapper priceMapper;

    @Test
    void startsNextAttemptWithSafeSha256Reference() {
        when(callMapper.selectMaxAttempt("logical-1")).thenReturn(2);
        when(callMapper.insert(any(LlmModelCallEntity.class))).thenAnswer(invocation -> {
            LlmModelCallEntity entity = invocation.getArgument(0);
            entity.setId(51L);
            return 1;
        });
        LlmCallContext context = LlmCallContext.builder("chapter_consensus", "generate")
                .workId(1L)
                .chapterId(2L)
                .logicalCallId("logical-1")
                .sourceFingerprint("context-hash")
                .promptTemplateVersion("template-v1")
                .build();

        LlmModelCallEntity call = service().start(config(), context);

        assertThat(call.getAttemptNo()).isEqualTo(3);
        assertThat(call.getRequestHash()).hasSize(64);
        assertThat(call.getRequestHash()).doesNotContain("context-hash");
        assertThat(call.getCallStatus()).isEqualTo("running");
        assertThat(call.getCostStatus()).isEqualTo("unpriced");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void calculatesEstimatedCostFromEffectivePriceVersion() {
        LlmModelCallEntity running = runningCall();
        when(callMapper.selectById(51L)).thenReturn(running);
        when(priceMapper.selectOne(any())).thenReturn(price());
        when(callMapper.update(any(), any())).thenReturn(1);

        service().succeed(
                51L,
                new LlmResponseMetadata("deepseek", "model", "stop", 1_000_000, 500_000, 1_500_000, "request-1"),
                1200L);

        ArgumentCaptor<UpdateWrapper<LlmModelCallEntity>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) UpdateWrapper.class);
        verify(callMapper).update(org.mockito.ArgumentMatchers.isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
                .contains("succeeded", 8L, new BigDecimal("0.28000000"), "USD", "estimated", 1200L);
    }

    private LlmCallObservationService service() {
        return new LlmCallObservationService(callMapper, priceMapper);
    }

    private LlmExecutionConfig config() {
        return new LlmExecutionConfig(
                new LlmProviderRuntimeConfig("deepseek", "https://example.test", "secret", "model"),
                new LlmExecutionConfigDescriptor("deepseek", "model", 2, 3));
    }

    private LlmModelCallEntity runningCall() {
        LlmModelCallEntity call = new LlmModelCallEntity();
        call.setId(51L);
        call.setProvider("deepseek");
        call.setModel("model");
        call.setCallStatus("running");
        call.setStartedAt(LocalDateTime.of(2026, 8, 4, 0, 0));
        call.setVersion(0);
        return call;
    }

    private LlmModelPriceEntity price() {
        LlmModelPriceEntity price = new LlmModelPriceEntity();
        price.setId(8L);
        price.setCurrency("USD");
        price.setInputCacheMissPricePerMillion(new BigDecimal("0.14000000"));
        price.setOutputPricePerMillion(new BigDecimal("0.28000000"));
        return price;
    }
}
