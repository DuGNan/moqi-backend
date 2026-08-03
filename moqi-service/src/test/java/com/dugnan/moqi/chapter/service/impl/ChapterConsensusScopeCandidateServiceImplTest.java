package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusValidator;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveCandidateRequest;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConsensusScopeCandidateEntity;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConsensusScopeCandidateMapper;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveScopeRequest;

/** @author dgn @date 2026-08-04 @description 验证未知共识候选可经乐观锁进入待确认状态。 */
@ExtendWith(MockitoExtension.class)
class ChapterConsensusScopeCandidateServiceImplTest {
    @Mock private ChapterConsensusScopeCandidateMapper mapper;

    @Mock private ChapterBriefMapper briefMapper;

    @Test void resolvesUnknownScope() {
        ChapterConsensusScopeCandidateEntity entity = new ChapterConsensusScopeCandidateEntity();
        entity.setId(1L); entity.setWorkId(2L); entity.setScope("unknown"); entity.setCandidateStatus("needs_scope"); entity.setVersion(0); entity.setDeleted(0);
        when(mapper.selectById(1L)).thenReturn(entity);
        when(mapper.update(any(), any())).thenAnswer(call -> { entity.setScope("character"); entity.setCandidateStatus("pending"); entity.setVersion(1); return 1; });
        var result = new ChapterConsensusScopeCandidateServiceImpl(mapper).resolveUnknownScope(1L, new ResolveScopeRequest(0, "character"));
        assertThat(result.candidateStatus()).isEqualTo("pending");
    }

    @Test
    void confirmsChapterCandidateByCreatingNewBriefDraft() {
        ChapterConsensusCodec codec = new ChapterConsensusCodec(new ObjectMapper(), new ChapterConsensusValidator());
        ChapterBriefEntity baseBrief = new ChapterBriefEntity();
        baseBrief.setId(9L);
        baseBrief.setWorkId(2L);
        baseBrief.setChapterId(3L);
        baseBrief.setBriefStatus("confirmed");
        baseBrief.setBriefContent(codec.write(content(List.of(decision("existing", "候选一", "pending")))));

        ChapterConsensusScopeCandidateEntity candidate = new ChapterConsensusScopeCandidateEntity();
        candidate.setId(1L);
        candidate.setWorkId(2L);
        candidate.setChapterId(3L);
        candidate.setBriefId(9L);
        candidate.setScope("chapter");
        candidate.setCandidateStatus("pending");
        candidate.setCandidateContentJson("{\"content\":\"{\\\"key\\\":\\\"existing\\\",\\\"title\\\":\\\"候选一\\\",\\\"status\\\":\\\"pending\\\",\\\"required\\\":true,\\\"prompt\\\":\\\"如何推进\\\",\\\"candidateSummary\\\":\\\"在雨夜揭示线索\\\",\\\"sourceMessageIds\\\":[],\\\"sourceQuotes\\\":[]}\"}");
        candidate.setVersion(0);
        candidate.setDeleted(0);
        when(mapper.selectById(1L)).thenReturn(candidate);
        when(briefMapper.findByIdAndChapterId(9L, 3L)).thenReturn(baseBrief);
        when(mapper.update(any(), any())).thenAnswer(call -> {
            candidate.setCandidateStatus("confirmed");
            candidate.setVersion(1);
            return 1;
        });

        ChapterConsensusScopeCandidateServiceImpl service = new ChapterConsensusScopeCandidateServiceImpl(
                mapper, briefMapper, codec, new ObjectMapper());
        var result = service.confirm(1L, new ResolveCandidateRequest(0));

        assertThat(result.candidateStatus()).isEqualTo("confirmed");
        org.mockito.ArgumentCaptor<ChapterBriefEntity> briefCaptor =
                org.mockito.ArgumentCaptor.forClass(ChapterBriefEntity.class);
        verify(briefMapper).insert(briefCaptor.capture());
        ChapterConsensusContentV1 draft = codec.read(briefCaptor.getValue().getBriefContent()).consensus();
        assertThat(briefCaptor.getValue().getBriefStatus()).isEqualTo("draft");
        assertThat(draft.decisions()).singleElement().satisfies(value -> {
            assertThat(value.status()).isEqualTo("confirmed");
            assertThat(value.candidateSummary()).isEqualTo("在雨夜揭示线索");
        });
    }

    @Test
    void rejectsStaleCandidateVersionBeforeWritingBrief() {
        ChapterConsensusScopeCandidateEntity candidate = new ChapterConsensusScopeCandidateEntity();
        candidate.setId(1L);
        candidate.setCandidateStatus("pending");
        candidate.setVersion(2);
        candidate.setDeleted(0);
        when(mapper.selectById(1L)).thenReturn(candidate);
        ChapterConsensusScopeCandidateServiceImpl service = new ChapterConsensusScopeCandidateServiceImpl(
                mapper, briefMapper, null, null);

        assertThatThrownBy(() -> service.confirm(1L, new ResolveCandidateRequest(1)))
                .isInstanceOf(RuntimeException.class);
        verify(briefMapper, never()).insert(any(ChapterBriefEntity.class));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void confirmsNonChapterCandidateWithoutWritingBrief() {
        ChapterConsensusScopeCandidateEntity candidate = new ChapterConsensusScopeCandidateEntity();
        candidate.setId(1L);
        candidate.setScope("character");
        candidate.setCandidateStatus("pending");
        candidate.setVersion(0);
        candidate.setDeleted(0);
        when(mapper.selectById(1L)).thenReturn(candidate);
        when(mapper.update(any(), any())).thenAnswer(call -> {
            candidate.setCandidateStatus("confirmed");
            candidate.setVersion(1);
            return 1;
        });
        ChapterConsensusScopeCandidateServiceImpl service = new ChapterConsensusScopeCandidateServiceImpl(
                mapper, briefMapper, null, null);

        assertThat(service.confirm(1L, new ResolveCandidateRequest(0)).candidateStatus()).isEqualTo("confirmed");
        verify(briefMapper, never()).insert(any(ChapterBriefEntity.class));
    }

    private ChapterConsensusContentV1 content(List<ChapterConsensusContentV1.Decision> decisions) {
        return new ChapterConsensusContentV1(1, "推进调查", new ChapterConsensusContentV1.StateChange("犹疑", "行动"),
                "获得线索", new ChapterConsensusContentV1.ReaderProgress("谜面", "谁在说谎"), List.of(), decisions);
    }

    private ChapterConsensusContentV1.Decision decision(String key, String title, String status) {
        return new ChapterConsensusContentV1.Decision(key, title, status, true, "如何推进", "候选结论", List.of(), List.of());
    }
}
