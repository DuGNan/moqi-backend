package com.dugnan.moqi.sourcechain;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 定义正式资产确认后向下游传播有效性状态的显式服务边界。
 */
public interface SourcePropagationService {
    void consensusConfirmed(Long chapterId, Long consensusId);
    void outlineConfirmed(Long chapterId, Long outlineId);
    void narrativePublished(Long workId, Long narrativePlanId);
    void scenePlanPublished(Long chapterId, Long scenePlanId);
    void scenePlanCreated(Long chapterId, Long scenePlanId);
    void generationCreated(Long chapterId, Long generationId);

    static SourcePropagationService noop() {
        return new SourcePropagationService() {
            @Override public void consensusConfirmed(Long chapterId, Long consensusId) { }
            @Override public void outlineConfirmed(Long chapterId, Long outlineId) { }
            @Override public void narrativePublished(Long workId, Long narrativePlanId) { }
            @Override public void scenePlanPublished(Long chapterId, Long scenePlanId) { }
            @Override public void scenePlanCreated(Long chapterId, Long scenePlanId) { }
            @Override public void generationCreated(Long chapterId, Long generationId) { }
        };
    }
}
