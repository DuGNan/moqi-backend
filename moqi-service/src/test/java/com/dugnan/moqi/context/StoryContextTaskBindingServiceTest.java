package com.dugnan.moqi.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证上下文快照与运行中 AI 任务的版本条件关联。
 */
@ExtendWith(MockitoExtension.class)
class StoryContextTaskBindingServiceTest {

    @Mock
    private StoryContextEngine contextEngine;
    @Mock
    private AiTaskMapper taskMapper;

    @Test
    void buildsSnapshotAndAdvancesTaskVersion() {
        StoryContextSnapshot snapshot = org.mockito.Mockito.mock(StoryContextSnapshot.class);
        when(snapshot.id()).thenReturn(88L);
        when(contextEngine.build(any(StoryContextBuildCommand.class))).thenReturn(snapshot);
        when(taskMapper.update(any(), any())).thenReturn(1);
        AiTaskEntity task = task();

        StoryContextSnapshot result = new StoryContextTaskBindingService(contextEngine, taskMapper)
                .buildAndAttach(org.mockito.Mockito.mock(StoryContextBuildCommand.class), task);

        assertThat(result).isSameAs(snapshot);
        assertThat(task.getContextSnapshotId()).isEqualTo(88L);
        assertThat(task.getVersion()).isEqualTo(2);
        verify(taskMapper).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void rejectsCancellationRaceWithoutReturningUnattachedSnapshot() {
        StoryContextSnapshot snapshot = org.mockito.Mockito.mock(StoryContextSnapshot.class);
        when(snapshot.id()).thenReturn(88L);
        when(contextEngine.build(any(StoryContextBuildCommand.class))).thenReturn(snapshot);
        when(taskMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> new StoryContextTaskBindingService(contextEngine, taskMapper)
                .buildAndAttach(org.mockito.Mockito.mock(StoryContextBuildCommand.class), task()))
                .isInstanceOf(StoryContextTaskBindingException.class);
    }

    @Test
    void reusesExistingSnapshotDuringRetryAndRecovery() {
        StoryContextEngine engineWithQuery = mock(
                StoryContextEngine.class,
                withSettings().extraInterfaces(StoryContextSnapshotQueryPort.class));
        StoryContextSnapshot snapshot = mock(StoryContextSnapshot.class);
        when(((StoryContextSnapshotQueryPort) engineWithQuery).load(77L)).thenReturn(snapshot);
        AiTaskEntity task = task();
        task.setContextSnapshotId(77L);

        StoryContextSnapshot result = new StoryContextTaskBindingService(engineWithQuery, taskMapper)
                .buildAndAttach(mock(StoryContextBuildCommand.class), task);

        assertThat(result).isSameAs(snapshot);
        verify(engineWithQuery, never()).build(any());
        verify(taskMapper, never()).update(any(), any());
    }

    private AiTaskEntity task() {
        AiTaskEntity task = new AiTaskEntity();
        task.setId(12L);
        task.setVersion(1);
        task.setTaskStatus("running");
        task.setDeleted(0);
        return task;
    }
}
