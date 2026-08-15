package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.CreateSceneGenerationRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.GenerationSceneList;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.GenerationSceneView;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.RetrySceneRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.RetryGenerationRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.SceneGenerationCreated;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 定义场景级候选正文生成、查询、取消和重试的应用服务。
 */
public interface SceneGenerationService {

    SceneGenerationCreated create(Long chapterId, CreateSceneGenerationRequest request);

    SceneGenerationCreated regenerate(Long generationId, CreateSceneGenerationRequest request);

    GenerationSceneList listScenes(Long generationId);

    GenerationSceneView getScene(Long generationId, Long sceneId);

    AgentRunView cancel(Long generationId);

    AgentRunView retryScene(Long generationId, Long sceneId, RetrySceneRequest request);

    /**
     * 重试失败的整章一次生成步骤，继续使用批次冻结输入。
     *
     * @param generationId 生成批次 ID
     * @param request 重试请求
     * @return Agent 运行详情
     */
    AgentRunView retryGeneration(Long generationId, RetryGenerationRequest request);

    /** 重试失败的整章收束步骤，不重新生成已完成的场景。 */
    AgentRunView retryCohesion(Long generationId);
}
