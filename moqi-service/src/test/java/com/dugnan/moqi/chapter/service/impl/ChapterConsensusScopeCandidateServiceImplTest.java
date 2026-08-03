package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.dugnan.moqi.chapter.entity.ChapterConsensusScopeCandidateEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConsensusScopeCandidateMapper;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveScopeRequest;

/** @author dgn @date 2026-08-04 @description 验证未知共识候选可经乐观锁进入待确认状态。 */
@ExtendWith(MockitoExtension.class)
class ChapterConsensusScopeCandidateServiceImplTest {
    @Mock private ChapterConsensusScopeCandidateMapper mapper;
    @Test void resolvesUnknownScope() {
        ChapterConsensusScopeCandidateEntity entity = new ChapterConsensusScopeCandidateEntity();
        entity.setId(1L); entity.setWorkId(2L); entity.setScope("unknown"); entity.setCandidateStatus("needs_scope"); entity.setVersion(0); entity.setDeleted(0);
        when(mapper.selectById(1L)).thenReturn(entity);
        when(mapper.update(any(), any())).thenAnswer(call -> { entity.setScope("character"); entity.setCandidateStatus("pending"); entity.setVersion(1); return 1; });
        var result = new ChapterConsensusScopeCandidateServiceImpl(mapper).resolveUnknownScope(1L, new ResolveScopeRequest(0, "character"));
        assertThat(result.candidateStatus()).isEqualTo("pending");
    }
}
