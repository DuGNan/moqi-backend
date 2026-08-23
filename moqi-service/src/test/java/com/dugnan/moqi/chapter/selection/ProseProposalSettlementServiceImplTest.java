package com.dugnan.moqi.chapter.selection;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.common.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 验证正文修改提案只在候选显式保存时按归属和冻结版本原子结算。
 */
class ProseProposalSettlementServiceImplTest {

    @Test
    void validatesAndMarksOnlyExplicitProposalIdsApplied() {
        ChapterSelectionAssistanceMapper mapper = mock(ChapterSelectionAssistanceMapper.class);
        ProseProposalSettlementServiceImpl service = new ProseProposalSettlementServiceImpl(mapper);
        ChapterProseCandidateEntity candidate = candidate();
        when(mapper.selectProposalsForUpdate(2L, 8L, List.of(9L))).thenReturn(List.of(proposal(9L)));
        when(mapper.markProposalsApplied(2L, 8L, List.of(9L), 4, "old-hash", 5, "new-hash"))
                .thenReturn(1);

        service.validateForSave(2L, candidate, List.of(9L));
        service.markApplied(2L, candidate, List.of(9L), 5, "new-hash");

        verify(mapper).markProposalsApplied(2L, 8L, List.of(9L), 4, "old-hash", 5, "new-hash");
        verify(mapper, never()).selectByIdForUpdate(10L);
    }

    @Test
    void rejectsProposalFromDifferentCandidateOrStaleTarget() {
        ChapterSelectionAssistanceMapper mapper = mock(ChapterSelectionAssistanceMapper.class);
        ProseProposalSettlementServiceImpl service = new ProseProposalSettlementServiceImpl(mapper);
        when(mapper.selectProposalsForUpdate(2L, 8L, List.of(9L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.validateForSave(2L, candidate(), List.of(9L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前候选");

        ChapterSelectionAssistanceEntity stale = proposal(9L);
        stale.setTargetContentHash("stale-hash");
        when(mapper.selectProposalsForUpdate(2L, 8L, List.of(9L))).thenReturn(List.of(stale));
        assertThatThrownBy(() -> service.validateForSave(2L, candidate(), List.of(9L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本或内容哈希已变化");
    }

    @Test
    void idempotentReplayRequiresSameRecordedCandidateResult() {
        ChapterSelectionAssistanceMapper mapper = mock(ChapterSelectionAssistanceMapper.class);
        ProseProposalSettlementServiceImpl service = new ProseProposalSettlementServiceImpl(mapper);
        ChapterSelectionAssistanceEntity applied = proposal(9L);
        applied.setProposalStatus("applied");
        applied.setAppliedCandidateVersion(5);
        applied.setAppliedCandidateHash("new-hash");
        when(mapper.selectProposalsForUpdate(2L, 8L, List.of(9L))).thenReturn(List.of(applied));

        service.requireApplied(2L, 8L, List.of(9L), 5, "new-hash");

        assertThatThrownBy(() -> service.requireApplied(2L, 8L, List.of(9L), 6, "other-hash"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结果不一致");
    }

    private static ChapterProseCandidateEntity candidate() {
        ChapterProseCandidateEntity candidate = new ChapterProseCandidateEntity();
        candidate.setId(8L);
        candidate.setChapterId(2L);
        candidate.setVersion(4);
        candidate.setContentHash("old-hash");
        return candidate;
    }

    private static ChapterSelectionAssistanceEntity proposal(Long id) {
        ChapterSelectionAssistanceEntity proposal = new ChapterSelectionAssistanceEntity();
        proposal.setId(id);
        proposal.setChapterId(2L);
        proposal.setTargetCandidateId(8L);
        proposal.setRequestStatus("ready");
        proposal.setProposalStatus("ready");
        proposal.setTargetContentVersion(4);
        proposal.setTargetContentHash("old-hash");
        proposal.setDeleted(0);
        return proposal;
    }
}
