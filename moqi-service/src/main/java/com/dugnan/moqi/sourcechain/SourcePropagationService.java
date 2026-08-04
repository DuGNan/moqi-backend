package com.dugnan.moqi.sourcechain;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 定义正式资产确认后向下游传播有效性状态的显式服务边界。
 */
public interface SourcePropagationService {
    /**
     * 传播已确认共识的影响。
     * @param chapterId 章节编号
     * @param consensusId 共识编号
     */
    void consensusConfirmed(Long chapterId, Long consensusId);
    /**
     * 传播已确认章纲的影响。
     * @param chapterId 章节编号
     * @param outlineId 章纲编号
     */
    void outlineConfirmed(Long chapterId, Long outlineId);
    /**
     * 传播已发布全书规划的影响。
     * @param workId 作品编号
     * @param narrativePlanId 规划编号
     */
    void narrativePublished(Long workId, Long narrativePlanId);
    /**
     * 传播已发布场景规划的影响。
     * @param chapterId 章节编号
     * @param scenePlanId 场景规划编号
     */
    void scenePlanPublished(Long chapterId, Long scenePlanId);
    /**
     * 为新场景规划建立来源快照。
     * @param chapterId 章节编号
     * @param scenePlanId 场景规划编号
     */
    void scenePlanCreated(Long chapterId, Long scenePlanId);
    /**
     * 为新正文批次建立来源快照。
     * @param chapterId 章节编号
     * @param generationId 正文批次编号
     */
    void generationCreated(Long chapterId, Long generationId);

    /**
     * 返回仅供旧单元测试使用的空传播实现。
     * @return 空传播服务
     */
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
