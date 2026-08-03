package com.dugnan.moqi.chapter.service.impl;

import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.CandidateList;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.CandidateView;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveCandidateRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveScopeRequest;
import com.dugnan.moqi.chapter.entity.ChapterConsensusScopeCandidateEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConsensusScopeCandidateMapper;
import com.dugnan.moqi.chapter.service.ChapterConsensusScopeCandidateService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 实现共识作用域候选的人工确认状态机，不直接写权威资产。
 */
@Service
public class ChapterConsensusScopeCandidateServiceImpl implements ChapterConsensusScopeCandidateService {
    private static final String STATUS_NEEDS_SCOPE = "needs_scope";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_REJECTED = "rejected";
    private final ChapterConsensusScopeCandidateMapper mapper;
    public ChapterConsensusScopeCandidateServiceImpl(ChapterConsensusScopeCandidateMapper mapper) { this.mapper = mapper; }
    @Override public CandidateList list(Long workId, Long chapterId, String status) {
        List<CandidateView> values = mapper.selectList(new LambdaQueryWrapper<ChapterConsensusScopeCandidateEntity>()
                .eq(ChapterConsensusScopeCandidateEntity::getWorkId, workId).eq(chapterId != null, ChapterConsensusScopeCandidateEntity::getChapterId, chapterId)
                .eq(status != null, ChapterConsensusScopeCandidateEntity::getCandidateStatus, status).eq(ChapterConsensusScopeCandidateEntity::getDeleted, 0)
                .orderByDesc(ChapterConsensusScopeCandidateEntity::getId)).stream().map(this::view).toList();
        return new CandidateList(workId, values);
    }
    @Override @Transactional(rollbackFor = RuntimeException.class) public CandidateView resolveUnknownScope(Long id, ResolveScopeRequest request) {
        ChapterConsensusScopeCandidateEntity entity = require(id);
        if (!STATUS_NEEDS_SCOPE.equals(entity.getCandidateStatus()) || request == null || request.baseVersion() == null || !validScope(request.scope())) { throw conflict(); }
        update(entity, STATUS_PENDING, request.scope()); return view(require(id));
    }
    @Override @Transactional(rollbackFor = RuntimeException.class) public CandidateView confirm(Long id, ResolveCandidateRequest request) {
        ChapterConsensusScopeCandidateEntity entity = require(id); if (!STATUS_PENDING.equals(entity.getCandidateStatus()) || request == null || request.baseVersion() == null) { throw conflict(); }
        update(entity, STATUS_CONFIRMED, null); return view(require(id));
    }
    @Override @Transactional(rollbackFor = RuntimeException.class) public CandidateView reject(Long id, ResolveCandidateRequest request) {
        ChapterConsensusScopeCandidateEntity entity = require(id); if (!STATUS_PENDING.equals(entity.getCandidateStatus()) || request == null || request.baseVersion() == null) { throw conflict(); }
        update(entity, STATUS_REJECTED, null); return view(require(id));
    }
    private void update(ChapterConsensusScopeCandidateEntity entity, String status, String scope) {
        int changed = mapper.update(null, new UpdateWrapper<ChapterConsensusScopeCandidateEntity>().eq("id", entity.getId()).eq("deleted", 0).eq("version", entity.getVersion())
                .eq("candidate_status", entity.getCandidateStatus()).set("candidate_status", status).set(scope != null, "scope", scope).setSql("version = version + 1"));
        if (changed != 1) { throw conflict(); }
    }
    private ChapterConsensusScopeCandidateEntity require(Long id) { ChapterConsensusScopeCandidateEntity value = id == null ? null : mapper.selectById(id); if (value == null || Integer.valueOf(1).equals(value.getDeleted())) { throw new BusinessException(ErrorCode.BAD_REQUEST, "共识作用域候选不存在"); } return value; }
    private boolean validScope(String scope) { return List.of("chapter", "character", "setting", "plot", "world", "foreshadowing", "unknown").contains(scope); }
    private BusinessException conflict() { return new BusinessException(ErrorCode.BAD_REQUEST, "共识作用域候选状态已变化"); }
    private CandidateView view(ChapterConsensusScopeCandidateEntity value) { return new CandidateView(value.getId(), value.getWorkId(), value.getChapterId(), value.getScope(), value.getCandidateStatus(), value.getCandidateContentJson(), value.getConfidence(), value.getVersion(), value.getGmtModified()); }
}
