package com.dugnan.moqi.chapter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.service.ProseObjectConversationService;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-26
 * @description 以章节锁和数据库唯一约束维护正文对象活动会话。
 */
@Service
public class ProseObjectConversationServiceImpl implements ProseObjectConversationService {

    private static final String TYPE_PROSE_OBJECT = "prose_object";
    private static final String STATUS_ACTIVE = "active";

    private final ChapterMapper chapterMapper;
    private final ChapterConversationMapper conversationMapper;
    private final ProseObjectTargetService targetService;

    public ProseObjectConversationServiceImpl(
            ChapterMapper chapterMapper,
            ChapterConversationMapper conversationMapper,
            ProseObjectTargetService targetService) {
        this.chapterMapper = chapterMapper;
        this.conversationMapper = conversationMapper;
        this.targetService = targetService;
    }

    @Override
    public ConversationDetail get(Long chapterId, String objectId) {
        targetService.resolve(chapterId, objectId);
        ChapterConversationEntity conversation = find(chapterId, objectId);
        return conversation == null ? null : detail(conversation);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ConversationDetail createOrGet(Long chapterId, String objectId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectByIdForUpdate(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        targetService.resolve(chapterId, objectId);
        ChapterConversationEntity conversation = find(chapterId, objectId);
        if (conversation == null) {
            conversation = new ChapterConversationEntity();
            conversation.setWorkId(chapter.getWorkId());
            conversation.setChapterId(chapterId);
            conversation.setConversationType(TYPE_PROSE_OBJECT);
            conversation.setConversationStatus(STATUS_ACTIVE);
            conversation.setTargetObjectId(objectId);
            conversation.setDeleted(0);
            conversation.setVersion(0);
            conversationMapper.insert(conversation);
        }
        return detail(conversation);
    }

    private ChapterConversationEntity find(Long chapterId, String objectId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<ChapterConversationEntity>()
                        .eq(ChapterConversationEntity::getChapterId, chapterId)
                        .eq(ChapterConversationEntity::getConversationType, TYPE_PROSE_OBJECT)
                        .eq(ChapterConversationEntity::getConversationStatus, STATUS_ACTIVE)
                        .eq(ChapterConversationEntity::getTargetObjectId, objectId)
                        .eq(ChapterConversationEntity::getDeleted, 0)
                        .orderByDesc(ChapterConversationEntity::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private ConversationDetail detail(ChapterConversationEntity entity) {
        return new ConversationDetail(entity.getId(), entity.getWorkId(), entity.getChapterId(),
                entity.getConversationType(), entity.getConversationStatus(), entity.getGmtCreate(),
                entity.getGmtModified(), entity.getTargetObjectId());
    }
}
