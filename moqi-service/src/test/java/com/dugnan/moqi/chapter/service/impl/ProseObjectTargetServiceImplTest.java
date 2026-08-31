package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/** 验证正文对象只能解析当前章节的服务端权威内容。 */
@ExtendWith(MockitoExtension.class)
class ProseObjectTargetServiceImplTest {

    @Mock private ChapterMapper chapterMapper;
    @Mock private ChapterProseCandidateMapper candidateMapper;
    private ProseObjectTargetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProseObjectTargetServiceImpl(chapterMapper, candidateMapper);
    }

    @Test
    void resolvesFormalAndFreezesCurrentContent() {
        ChapterEntity chapter = chapter();
        when(chapterMapper.selectById(2L)).thenReturn(chapter);

        var target = service.resolve(2L, "formal:2");

        assertThat(target.objectKind()).isEqualTo("formal");
        assertThat(target.content()).isEqualTo("正式正文");
        assertThat(target.contentHash()).hasSize(64);
        assertThat(target.promptText()).contains("当前讨论对象：正式正文", "当前正文：");
    }

    @Test
    void rejectsCandidateFromAnotherChapter() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter());
        ChapterProseCandidateEntity candidate = new ChapterProseCandidateEntity();
        candidate.setId(8L);
        candidate.setChapterId(3L);
        candidate.setDeleted(0);
        when(candidateMapper.selectById(8L)).thenReturn(candidate);

        assertThatThrownBy(() -> service.resolve(2L, "candidate:8"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正文候选不存在");
    }

    private ChapterEntity chapter() {
        ChapterEntity value = new ChapterEntity();
        value.setId(2L);
        value.setWorkId(1L);
        value.setContent("正式正文");
        value.setVersion(3);
        value.setDeleted(0);
        return value;
    }
}
