package com.dugnan.moqi.chapter.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import com.dugnan.moqi.chapter.entity.BoundedChapterRevisionEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.mapper.BoundedChapterRevisionMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.service.ProseCandidateMaterializationService;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 将完整生成稿和有界修订稿幂等物化为稳定正文候选。
 */
@Service
public class ProseCandidateMaterializationServiceImpl implements ProseCandidateMaterializationService {

    private static final String GENERATION_ACCEPTED = "accepted";
    private static final String GENERATION_REJECTED = "rejected";
    private static final String GENERATION_SUPERSEDED = "superseded";

    private final ChapterProseCandidateMapper candidateMapper;
    private final BoundedChapterRevisionMapper boundedRevisionMapper;
    private final ChapterGenerationMapper generationMapper;

    public ProseCandidateMaterializationServiceImpl(
            ChapterProseCandidateMapper candidateMapper,
            BoundedChapterRevisionMapper boundedRevisionMapper,
            ChapterGenerationMapper generationMapper) {
        this.candidateMapper = candidateMapper;
        this.boundedRevisionMapper = boundedRevisionMapper;
        this.generationMapper = generationMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RuntimeException.class)
    public void materialize(ChapterGenerationEntity generation) {
        doMaterialize(generation);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RuntimeException.class)
    public void materializeByGenerationId(Long generationId) {
        if (generationId == null) {
            return;
        }
        ChapterGenerationEntity generation = generationMapper.selectById(generationId);
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted())) {
            throw new IllegalStateException("已提交生成记录不存在，generationId=" + generationId);
        }
        doMaterialize(generation);
    }

    private void doMaterialize(ChapterGenerationEntity generation) {
        if (generation == null || generation.getId() == null || generation.getGeneratedContent() == null) {
            return;
        }
        ChapterProseCandidateEntity existing = findBySourceGeneration(generation.getId());
        if (existing != null) {
            synchronizeChapterStatuses(generation.getChapterId());
            return;
        }
        ChapterProseCandidateEntity parent = generation.getBaseGenerationId() == null
                ? null : findBySourceGeneration(generation.getBaseGenerationId());
        BoundedChapterRevisionEntity bounded = boundedRevisionMapper.selectOne(
                new LambdaQueryWrapper<BoundedChapterRevisionEntity>()
                        .eq(BoundedChapterRevisionEntity::getResultGenerationId, generation.getId())
                        .eq(BoundedChapterRevisionEntity::getDeleted, 0));
        ChapterProseCandidateEntity candidate = new ChapterProseCandidateEntity();
        candidate.setWorkId(generation.getWorkId());
        candidate.setChapterId(generation.getChapterId());
        candidate.setRootCandidateId(parent == null ? null
                : Objects.requireNonNullElse(parent.getRootCandidateId(), parent.getId()));
        candidate.setParentCandidateId(parent == null ? null : parent.getId());
        candidate.setSourceKind(bounded == null ? "generation" : "bounded_revision");
        candidate.setSourceGenerationId(generation.getId());
        candidate.setSourceBoundedRevisionId(bounded == null ? null : bounded.getId());
        candidate.setQualityGenerationId(generation.getId());
        candidate.setQualityRequestStatus("pending");
        candidate.setCandidateStatus(historyStatus(generation.getGenerationStatus()) ? "history" : "active");
        candidate.setAdoptionStatus(adoptionStatus(generation.getGenerationStatus()));
        candidate.setContent(generation.getGeneratedContent());
        candidate.setContentHash(hash(generation.getGeneratedContent()));
        candidate.setWordCount(wordCount(generation.getGeneratedContent()));
        candidate.setDeleted(0);
        candidate.setVersion(0);
        try {
            candidateMapper.insert(candidate);
        } catch (DuplicateKeyException duplicate) {
            synchronizeChapterStatuses(generation.getChapterId());
            return;
        }
        if (candidate.getRootCandidateId() == null) {
            candidateMapper.update(null, new UpdateWrapper<ChapterProseCandidateEntity>()
                    .eq("id", candidate.getId())
                    .isNull("root_candidate_id")
                    .set("root_candidate_id", candidate.getId()));
        }
    }

    @Override
    public void synchronizeChapterStatuses(Long chapterId) {
        if (chapterId != null) {
            candidateMapper.synchronizeGenerationStatuses(chapterId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RuntimeException.class)
    public void markQualityRequested(Long generationId) {
        updateQualityRequestStatus(generationId, "requested");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RuntimeException.class)
    public void markQualityUnavailable(Long generationId) {
        updateQualityRequestStatus(generationId, "unavailable");
    }

    private void updateQualityRequestStatus(Long generationId, String status) {
        if (generationId != null) {
            candidateMapper.updateQualityRequestStatus(generationId, status);
        }
    }

    private ChapterProseCandidateEntity findBySourceGeneration(Long generationId) {
        return candidateMapper.selectOne(new LambdaQueryWrapper<ChapterProseCandidateEntity>()
                .eq(ChapterProseCandidateEntity::getSourceGenerationId, generationId)
                .eq(ChapterProseCandidateEntity::getDeleted, 0));
    }

    private boolean historyStatus(String status) {
        return GENERATION_REJECTED.equals(status) || GENERATION_SUPERSEDED.equals(status);
    }

    private String adoptionStatus(String status) {
        if (GENERATION_ACCEPTED.equals(status)) {
            return "adopted";
        }
        return GENERATION_SUPERSEDED.equals(status) ? "replaced" : "unadopted";
    }

    private int wordCount(String content) {
        return content == null ? 0 : content.codePointCount(0, content.length());
    }

    private String hash(String content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算正文候选内容哈希", exception);
        }
    }
}
