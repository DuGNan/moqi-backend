package com.dugnan.moqi.chapter.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.event.ChapterGenerationCompletedEvent;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;

/**
 * @author dgn
 * @date 2026-10-20
 * @description 验证服务恢复使用已提交批次重新进入幂等写回，而不重新执行正文生成。
 */
class ProseGenerationRecoveryRunnerTest {

    @Test
    void replaysPersistedPendingWritebacks() {
        ChapterGenerationMapper mapper = mock(ChapterGenerationMapper.class);
        ProseCandidateGenerationCompletedListener listener = mock(ProseCandidateGenerationCompletedListener.class);
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(60L);
        generation.setChapterId(423L);
        when(mapper.findPendingTargetWritebacks()).thenReturn(List.of(generation));
        new ProseGenerationRecoveryRunner(mapper, listener).recover();
        verify(listener).handle(new ChapterGenerationCompletedEvent(423L, 60L));
    }
}
