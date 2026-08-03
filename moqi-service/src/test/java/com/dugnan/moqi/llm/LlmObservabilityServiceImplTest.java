package com.dugnan.moqi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.entity.LlmModelCallEntity;
import com.dugnan.moqi.chapter.mapper.LlmModelCallMapper;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallDetail;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallQuery;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmSummaryQuery;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 验证模型调用查询的用户隔离、分页白名单和敏感响应边界。
 */
@ExtendWith(MockitoExtension.class)
class LlmObservabilityServiceImplTest {

    @Mock
    private LlmModelCallMapper callMapper;

    @Test
    void listsOnlyCurrentUserWithBoundedPagination() {
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 2, 0, 0);
        when(callMapper.countRecent(
                eq("local-user"), eq(from), eq(to), eq(3L), eq(4L), eq("deepseek"), eq("model"),
                eq("workflow"), eq("failed"))).thenReturn(1L);
        when(callMapper.selectRecent(
                eq("local-user"), eq(from), eq(to), eq(3L), eq(4L), eq("deepseek"), eq("model"),
                eq("workflow"), eq("failed"), eq(20L), eq(20))).thenReturn(List.of(call()));

        var page = service().list(new LlmCallQuery(
                from, to, 3L, 4L, " deepseek ", "model", "workflow", "failed", 2, 20));

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).hasSize(1);
        verify(callMapper).selectRecent(
                "local-user", from, to, 3L, 4L, "deepseek", "model", "workflow", "failed", 20L, 20);
    }

    @Test
    void rejectsUnboundedPageAndDynamicGroupExpression() {
        assertThatThrownBy(() -> service().list(new LlmCallQuery(
                null, null, null, null, null, null, null, null, 1, 101)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().summarize(new LlmSummaryQuery(
                null, null, null, null, null, null, "date, sleep(1)")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void responseContractDoesNotExposeSensitivePayloadFields() {
        assertThat(Arrays.stream(LlmCallDetail.class.getRecordComponents())
                .map(component -> component.getName())
                .toList())
                .doesNotContain("apiKey", "prompt", "content", "reasoning", "errorMessage", "requestHash");
    }

    private LlmObservabilityServiceImpl service() {
        return new LlmObservabilityServiceImpl(callMapper);
    }

    private LlmModelCallEntity call() {
        LlmModelCallEntity call = new LlmModelCallEntity();
        call.setId(1L);
        call.setCallStatus("failed");
        return call;
    }
}
