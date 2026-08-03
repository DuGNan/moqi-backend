package com.dugnan.moqi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.entity.LlmModelCallEntity;
import com.dugnan.moqi.chapter.mapper.LlmModelCallMapper;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 验证服务重启后运行中模型调用会被收敛为可解释的未知终态。
 */
@ExtendWith(MockitoExtension.class)
class LlmCallRecoveryRunnerTest {

    @Mock
    private LlmModelCallMapper callMapper;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void marksPreviousProcessCallsUnknown() throws Exception {
        when(callMapper.update(any(), any())).thenReturn(1);

        new LlmCallRecoveryRunner(callMapper).run(null);

        ArgumentCaptor<UpdateWrapper<LlmModelCallEntity>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) UpdateWrapper.class);
        verify(callMapper).update(org.mockito.ArgumentMatchers.isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("call_status", "started_at");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
                .contains("running", "unknown", "process_restart", "PROCESS_RESTART");
    }
}
