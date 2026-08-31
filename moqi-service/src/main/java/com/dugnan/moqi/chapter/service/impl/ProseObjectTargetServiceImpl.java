package com.dugnan.moqi.chapter.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-26
 * @description 从章节与正文候选中解析对象级会话的权威目标快照。
 */
@Service
public class ProseObjectTargetServiceImpl implements ProseObjectTargetService {

    private static final String FORMAL_PREFIX = "formal:";
    private static final String CANDIDATE_PREFIX = "candidate:";

    private final ChapterMapper chapterMapper;
    private final ChapterProseCandidateMapper candidateMapper;

    public ProseObjectTargetServiceImpl(
            ChapterMapper chapterMapper,
            ChapterProseCandidateMapper candidateMapper) {
        this.chapterMapper = chapterMapper;
        this.candidateMapper = candidateMapper;
    }

    @Override
    public ProseObjectTarget resolve(Long chapterId, String objectId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        if (objectId != null && objectId.equals(FORMAL_PREFIX + chapterId)) {
            String content = chapter.getContent() == null ? "" : chapter.getContent();
            return new ProseObjectTarget(objectId, "formal", version(chapter.getVersion()), hash(content),
                    content, "作者当前保存的正式正文");
        }
        Long candidateId = parseCandidateId(objectId);
        ChapterProseCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || Integer.valueOf(1).equals(candidate.getDeleted())
                || !chapterId.equals(candidate.getChapterId())) {
            throw new BusinessException(ErrorCode.PROSE_CANDIDATE_NOT_FOUND, "正文候选不存在");
        }
        return new ProseObjectTarget(objectId, "candidate", version(candidate.getVersion()),
                candidate.getContentHash(), candidate.getContent() == null ? "" : candidate.getContent(),
                sourceDescription(candidate));
    }

    private Long parseCandidateId(String objectId) {
        try {
            if (!StringUtils.hasText(objectId) || !objectId.startsWith(CANDIDATE_PREFIX)) {
                throw new NumberFormatException();
            }
            Long value = Long.valueOf(objectId.substring(CANDIDATE_PREFIX.length()));
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文对象 ID 格式不正确");
        }
    }

    private String sourceDescription(ChapterProseCandidateEntity candidate) {
        String sourceKind = candidate.getSourceKind() == null ? "" : candidate.getSourceKind();
        return switch (sourceKind) {
            case "generation" -> "基于本章权威规划生成的候选";
            case "bounded_revision" -> "基于已有正文的有界修订候选";
            case "assistance" -> "由作者发起正文改写形成的候选";
            default -> "作者正在编辑的正文候选";
        };
    }

    private int version(Integer value) {
        return value == null ? 0 : value;
    }

    private String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
