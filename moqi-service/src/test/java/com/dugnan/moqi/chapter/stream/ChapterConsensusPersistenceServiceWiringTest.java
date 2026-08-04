package com.dugnan.moqi.chapter.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusValidator;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConsensusScopeCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 验证章节共识持久化服务能够由 Spring 选择完整依赖构造器完成装配。
 */
class ChapterConsensusPersistenceServiceWiringTest {

    /**
     * 验证存在兼容构造器时 Spring 仍使用完整依赖构造器。
     */
    @Test
    void createsBeanWithCompleteConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiTaskMapper.class, () -> mock(AiTaskMapper.class));
            context.registerBean(ChapterBriefMapper.class, () -> mock(ChapterBriefMapper.class));
            context.registerBean(
                    ChapterConversationMessageMapper.class,
                    () -> mock(ChapterConversationMessageMapper.class));
            context.registerBean(ChapterConsensusValidator.class, ChapterConsensusValidator::new);
            context.registerBean(ChapterConsensusCodec.class, () -> mock(ChapterConsensusCodec.class));
            context.registerBean(
                    ChapterConsensusScopeCandidateMapper.class,
                    () -> mock(ChapterConsensusScopeCandidateMapper.class));
            context.register(ChapterConsensusPersistenceService.class);

            context.refresh();

            assertThat(context.getBean(ChapterConsensusPersistenceService.class)).isNotNull();
        }
    }
}
