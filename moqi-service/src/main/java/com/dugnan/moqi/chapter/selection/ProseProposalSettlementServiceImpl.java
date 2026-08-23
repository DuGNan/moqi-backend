package com.dugnan.moqi.chapter.selection;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 以稳定锁序校验修改提案归属，并原子记录候选保存结果。
 */
@Service
public class ProseProposalSettlementServiceImpl implements ProseProposalSettlementService {

    private static final Set<String> READY_STATUSES = Set.of("ready", "review_required");
    private static final String PROPOSAL_READY = "ready";
    private static final String PROPOSAL_APPLIED = "applied";
    private static final int MAX_PROPOSAL_IDS_PER_SAVE = 100;

    private final ChapterSelectionAssistanceMapper assistanceMapper;

    public ProseProposalSettlementServiceImpl(ChapterSelectionAssistanceMapper assistanceMapper) {
        this.assistanceMapper = assistanceMapper;
    }

    @Override
    public void validateForSave(
            Long chapterId,
            ChapterProseCandidateEntity candidate,
            List<Long> proposalIds) {
        List<Long> normalizedIds = normalize(proposalIds);
        if (normalizedIds.isEmpty()) {
            return;
        }
        List<ChapterSelectionAssistanceEntity> proposals = assistanceMapper.selectProposalsForUpdate(
                chapterId, candidate.getId(), normalizedIds);
        if (proposals.size() != normalizedIds.size()) {
            throw conflict("部分正文修改提案不存在或不属于当前候选");
        }
        for (ChapterSelectionAssistanceEntity proposal : proposals) {
            if (!READY_STATUSES.contains(proposal.getRequestStatus())
                    || !PROPOSAL_READY.equals(proposal.getProposalStatus())
                    || !Objects.equals(proposal.getTargetContentVersion(), candidate.getVersion())
                    || !Objects.equals(proposal.getTargetContentHash(), candidate.getContentHash())) {
                throw conflict("正文修改提案状态、目标版本或内容哈希已变化");
            }
        }
    }

    @Override
    public void markApplied(
            Long chapterId,
            ChapterProseCandidateEntity candidate,
            List<Long> proposalIds,
            Integer resultVersion,
            String resultHash) {
        List<Long> normalizedIds = normalize(proposalIds);
        if (normalizedIds.isEmpty()) {
            return;
        }
        int updated = assistanceMapper.markProposalsApplied(chapterId, candidate.getId(), normalizedIds,
                candidate.getVersion(), candidate.getContentHash(), resultVersion, resultHash);
        if (updated != normalizedIds.size()) {
            throw conflict("正文修改提案结算冲突");
        }
    }

    @Override
    public void requireApplied(
            Long chapterId,
            Long candidateId,
            List<Long> proposalIds,
            Integer resultVersion,
            String resultHash) {
        List<Long> normalizedIds = normalize(proposalIds);
        if (normalizedIds.isEmpty()) {
            return;
        }
        List<ChapterSelectionAssistanceEntity> proposals = assistanceMapper.selectProposalsForUpdate(
                chapterId, candidateId, normalizedIds);
        boolean matches = proposals.size() == normalizedIds.size() && proposals.stream().allMatch(proposal ->
                PROPOSAL_APPLIED.equals(proposal.getProposalStatus())
                        && Objects.equals(proposal.getAppliedCandidateVersion(), resultVersion)
                        && Objects.equals(proposal.getAppliedCandidateHash(), resultHash));
        if (!matches) {
            throw conflict("重复保存与已应用的正文修改提案结果不一致");
        }
    }

    private List<Long> normalize(List<Long> proposalIds) {
        if (proposalIds == null || proposalIds.isEmpty()) {
            return List.of();
        }
        if (proposalIds.size() > MAX_PROPOSAL_IDS_PER_SAVE || proposalIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "appliedProposalIds 不符合候选保存契约");
        }
        List<Long> normalized = proposalIds.stream().distinct().sorted().toList();
        if (normalized.size() != proposalIds.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "appliedProposalIds 不能重复");
        }
        return normalized;
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.PROSE_CANDIDATE_CONFLICT, message);
    }
}
