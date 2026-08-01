package com.dugnan.moqi.chapter.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.agent.event.AgentRunEvent;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证 Agent Run 失败会同步场景候选与生成批次的持久化状态。
 */
@ExtendWith(MockitoExtension.class)
class SceneGenerationRunLifecycleListenerTest {

    @Mock
    private ChapterGenerationMapper generationMapper;
    @Mock
    private ChapterGenerationSceneMapper sceneMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void marksRunningSceneFailedAndPublishesSceneFailure() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(71L);
        generation.setChapterId(12L);
        generation.setAgentRunId(61L);
        generation.setDeleted(0);
        when(generationMapper.selectOne(any())).thenReturn(generation);
        when(sceneMapper.update(any(), any())).thenReturn(1);
        when(generationMapper.update(any(), any())).thenReturn(1);
        SceneGenerationRunLifecycleListener listener = new SceneGenerationRunLifecycleListener(
                generationMapper, sceneMapper, eventPublisher);

        listener.handle(AgentRunEvent.updated(12L, 61L, "scene_novel_generation", 41L,
                "failed", 81L, "generate_scene:scene-1", "failed", 3L, null));

        verify(sceneMapper).update(any(), any());
        verify(generationMapper, times(1)).update(any(), any());
        verify(eventPublisher, times(2)).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
    }
}
