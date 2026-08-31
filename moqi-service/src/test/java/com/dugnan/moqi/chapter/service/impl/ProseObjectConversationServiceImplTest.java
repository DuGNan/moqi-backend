package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService.ProseObjectTarget;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/** 验证正文对象会话按稳定对象作用域隔离并幂等创建。 */
@ExtendWith(MockitoExtension.class)
class ProseObjectConversationServiceImplTest {

    @Mock private ChapterMapper chapterMapper;
    @Mock private ChapterConversationMapper conversationMapper;
    @Mock private ProseObjectTargetService targetService;
    private ProseObjectConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProseObjectConversationServiceImpl(chapterMapper, conversationMapper, targetService);
    }

    @Test
    void createsFormalAndCandidateConversationsWithoutSharingScope() {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(2L);
        chapter.setWorkId(1L);
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter);
        when(targetService.resolve(2L, "formal:2")).thenReturn(target("formal:2"));
        when(targetService.resolve(2L, "candidate:8")).thenReturn(target("candidate:8"));
        when(conversationMapper.selectList(any())).thenReturn(List.of());
        when(conversationMapper.insert(any(ChapterConversationEntity.class))).thenAnswer(invocation -> {
            ChapterConversationEntity value = invocation.getArgument(0);
            value.setId("formal:2".equals(value.getTargetObjectId()) ? 10L : 11L);
            return 1;
        });

        var formal = service.createOrGet(2L, "formal:2");
        var candidate = service.createOrGet(2L, "candidate:8");

        assertThat(formal.id()).isEqualTo(10L);
        assertThat(formal.targetObjectId()).isEqualTo("formal:2");
        assertThat(candidate.id()).isEqualTo(11L);
        assertThat(candidate.targetObjectId()).isEqualTo("candidate:8");
        verify(conversationMapper, org.mockito.Mockito.times(2))
                .insert(any(ChapterConversationEntity.class));
    }

    @Test
    void reusesExistingConversationWithoutCreatingAnother() {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(2L);
        chapter.setWorkId(1L);
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter);
        when(targetService.resolve(2L, "candidate:8")).thenReturn(target("candidate:8"));
        ChapterConversationEntity existing = new ChapterConversationEntity();
        existing.setId(11L);
        existing.setWorkId(1L);
        existing.setChapterId(2L);
        existing.setConversationType("prose_object");
        existing.setConversationStatus("active");
        existing.setTargetObjectId("candidate:8");
        when(conversationMapper.selectList(any())).thenReturn(List.of(existing));

        var result = service.createOrGet(2L, "candidate:8");

        assertThat(result.id()).isEqualTo(11L);
        verify(conversationMapper, never()).insert(any(ChapterConversationEntity.class));
    }

    private ProseObjectTarget target(String objectId) {
        return new ProseObjectTarget(objectId, objectId.startsWith("formal") ? "formal" : "candidate",
                1, "hash", "正文", "来源");
    }
}
