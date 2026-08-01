package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.CreateSceneGenerationRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.GenerationSceneList;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.GenerationSceneView;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.RetrySceneRequest;
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
}
