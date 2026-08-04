package com.dugnan.moqi.chapter.service.impl;

import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.CandidateList;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.CandidateView;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveCandidateRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveScopeRequest;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConsensusScopeCandidateEntity;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConsensusScopeCandidateMapper;
import com.dugnan.moqi.chapter.service.ChapterConsensusScopeCandidateService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 实现共识作用域候选的人工确认状态机；章节候选只会派生新的 Brief Draft。
 */
@Service
public class ChapterConsensusScopeCandidateServiceImpl implements ChapterConsensusScopeCandidateService {

    private static final String STATUS_NEEDS_SCOPE = "needs_scope";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_REJECTED = "rejected";
    private static final String SCOPE_CHAPTER = "chapter";

    private final ChapterConsensusScopeCandidateMapper mapper;
    private final ChapterBriefMapper briefMapper;
    private final ChapterConsensusCodec codec;
    private final ObjectMapper objectMapper;

    /** 仅供既有轻量单元测试使用。 */
    public ChapterConsensusScopeCandidateServiceImpl(ChapterConsensusScopeCandidateMapper mapper) {
        this(mapper, null, null, null);
    }

    @Autowired
    public ChapterConsensusScopeCandidateServiceImpl(
            ChapterConsensusScopeCandidateMapper mapper,
            ChapterBriefMapper briefMapper,
            ChapterConsensusCodec codec,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.briefMapper = briefMapper;
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    @Override
    public CandidateList list(Long workId, Long chapterId, String status) {
        List<CandidateView> candidates = mapper.selectList(new LambdaQueryWrapper<ChapterConsensusScopeCandidateEntity>()
                .eq(ChapterConsensusScopeCandidateEntity::getWorkId, workId)
                .eq(chapterId != null, ChapterConsensusScopeCandidateEntity::getChapterId, chapterId)
                .eq(status != null, ChapterConsensusScopeCandidateEntity::getCandidateStatus, status)
                .eq(ChapterConsensusScopeCandidateEntity::getDeleted, 0)
                .orderByDesc(ChapterConsensusScopeCandidateEntity::getId)).stream()
                .map(this::view)
                .toList();
        return new CandidateList(workId, candidates);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public CandidateView resolveUnknownScope(Long id, ResolveScopeRequest request) {
        ChapterConsensusScopeCandidateEntity candidate = require(id);
        if (!STATUS_NEEDS_SCOPE.equals(candidate.getCandidateStatus())
                || request == null
                || !matchesVersion(candidate, request.baseVersion())
                || !validScope(request.scope())) {
            throw conflict();
        }
        update(candidate, STATUS_PENDING, request.scope());
        return view(require(id));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public CandidateView confirm(Long id, ResolveCandidateRequest request) {
        ChapterConsensusScopeCandidateEntity candidate = requirePending(id, request);
        if (SCOPE_CHAPTER.equals(candidate.getScope())) {
            createChapterBriefDraft(candidate);
        }
        update(candidate, STATUS_CONFIRMED, null);
        return view(require(id));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public CandidateView reject(Long id, ResolveCandidateRequest request) {
        ChapterConsensusScopeCandidateEntity candidate = requirePending(id, request);
        update(candidate, STATUS_REJECTED, null);
        return view(require(id));
    }

    private ChapterConsensusScopeCandidateEntity requirePending(Long id, ResolveCandidateRequest request) {
        ChapterConsensusScopeCandidateEntity candidate = require(id);
        if (!STATUS_PENDING.equals(candidate.getCandidateStatus())
                || request == null
                || !matchesVersion(candidate, request.baseVersion())) {
            throw conflict();
        }
        return candidate;
    }

    private boolean matchesVersion(ChapterConsensusScopeCandidateEntity candidate, Integer baseVersion) {
        return baseVersion != null && baseVersion.equals(candidate.getVersion());
    }

    /**
     * 章节候选的 content 是一个完整 Decision JSON 补丁。它先合并到候选创建时绑定的 Brief，
     * 再以新行保存 Draft，因而不会覆盖任何已存在的权威 Brief。
     */
    private void createChapterBriefDraft(ChapterConsensusScopeCandidateEntity candidate) {
        if (briefMapper == null || codec == null || objectMapper == null
                || candidate.getBriefId() == null || candidate.getChapterId() == null) {
            throw invalid("章节候选缺少可用的 Brief 基线");
        }
        ChapterBriefEntity baseBrief = briefMapper.findByIdAndChapterId(candidate.getBriefId(), candidate.getChapterId());
        if (baseBrief == null || !candidate.getWorkId().equals(baseBrief.getWorkId())) {
            throw invalid("章节候选的 Brief 基线已不存在或不属于当前作品");
        }
        ChapterConsensusContentV1 baseContent = codec.read(baseBrief.getBriefContent()).consensus();
        if (baseContent == null) {
            throw invalid("章节候选不能应用到非结构化 Brief");
        }
        Decision patch = readDecisionPatch(candidate.getCandidateContentJson());
        List<Decision> decisions = new ArrayList<>(baseContent.decisions());
        int index = decisionIndex(decisions, patch.key());
        if (index >= 0) {
            decisions.set(index, patch);
        } else {
            decisions.add(patch);
        }
        ChapterConsensusContentV1 merged = new ChapterConsensusContentV1(
                baseContent.schemaVersion(),
                baseContent.chapterTask(),
                baseContent.stateChange(),
                baseContent.keyPush(),
                baseContent.readerProgress(),
                baseContent.writingBoundaries(),
                decisions,
                baseContent.scopeCandidates());
        ChapterBriefEntity draft = new ChapterBriefEntity();
        draft.setWorkId(baseBrief.getWorkId());
        draft.setChapterId(baseBrief.getChapterId());
        draft.setBriefStatus("draft");
        draft.setBriefContent(codec.write(merged));
        draft.setDeleted(0);
        draft.setVersion(0);
        briefMapper.insert(draft);
    }

    private Decision readDecisionPatch(String candidateContentJson) {
        try {
            JsonNode root = objectMapper.readTree(candidateContentJson);
            JsonNode content = root == null ? null : root.get("content");
            if (content == null || !content.isTextual()) {
                throw invalid("章节候选必须包含 Decision JSON 补丁");
            }
            Decision parsed = objectMapper.readValue(content.textValue(), Decision.class);
            return new Decision(
                    parsed.key(), parsed.title(), "confirmed", parsed.required(), parsed.prompt(),
                    parsed.candidateSummary(), parsed.sourceMessageIds(), parsed.sourceQuotes());
        } catch (JsonProcessingException exception) {
            throw invalid("章节候选包含无法识别的 Decision JSON 补丁");
        }
    }

    private int decisionIndex(List<Decision> decisions, String key) {
        for (int index = 0; index < decisions.size(); index++) {
            if (decisions.get(index).key().equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private void update(ChapterConsensusScopeCandidateEntity candidate, String status, String scope) {
        int changed = mapper.update(null, new UpdateWrapper<ChapterConsensusScopeCandidateEntity>()
                .eq("id", candidate.getId())
                .eq("deleted", 0)
                .eq("version", candidate.getVersion())
                .eq("candidate_status", candidate.getCandidateStatus())
                .set("candidate_status", status)
                .set(scope != null, "scope", scope)
                .setSql("version = version + 1"));
        if (changed != 1) {
            throw conflict();
        }
    }

    private ChapterConsensusScopeCandidateEntity require(Long id) {
        ChapterConsensusScopeCandidateEntity value = id == null ? null : mapper.selectById(id);
        if (value == null || Integer.valueOf(1).equals(value.getDeleted())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "共识作用域候选不存在");
        }
        return value;
    }

    private boolean validScope(String scope) {
        return List.of(SCOPE_CHAPTER, "character", "setting", "plot", "world", "foreshadowing", "unknown")
                .contains(scope);
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "共识作用域候选状态或版本已变化");
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.CHAPTER_CONSENSUS_INVALID, message);
    }

    private CandidateView view(ChapterConsensusScopeCandidateEntity value) {
        return new CandidateView(value.getId(), value.getWorkId(), value.getChapterId(), value.getScope(),
                value.getCandidateStatus(), value.getCandidateContentJson(), value.getConfidence(), value.getVersion(),
                value.getGmtModified());
    }
}
