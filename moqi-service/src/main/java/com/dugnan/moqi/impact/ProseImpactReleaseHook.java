package com.dugnan.moqi.impact;

import java.util.List;

/**
 * @author dgn
 * @description 隔离影响报告门禁与 Story Release 原子激活的扩展边界。
 */
public interface ProseImpactReleaseHook {
    /**
     * 收集工作区内所有目标 revision 的影响报告阻塞项，供准备阶段统一门禁。
     *
     * @param workId 作品 ID
     * @param workspaceId 工作区 ID
     * @return 影响报告阻塞项
     */
    List<String> workspaceBlockingItems(Long workId, Long workspaceId);
    /**
     * 汇总工作区影响范围、报告状态和受影响资产，供只读展示使用。
     *
     * @param workId 作品 ID
     * @param workspaceId 工作区 ID
     * @return 结构化影响摘要
     */
    ProseImpactModels.WorkspaceImpactSummary workspaceSummary(Long workId, Long workspaceId);
    /**
     * 在 Story Release 原子切换事务中激活知识来源映射并传播来源失效状态。
     *
     * @param workId 作品 ID
     * @param releaseId 新 Story Release ID
     * @param previousReleaseId 旧 Story Release ID
     * @param rollbackTargetReleaseId 回退目标 ID
     */
    void activateRelease(Long workId, Long releaseId, Long previousReleaseId, Long rollbackTargetReleaseId);

    /**
     * 创建不启用影响传播能力的兼容实现，供尚未接入该扩展点的调用方使用。
     *
     * @return 兼容旧单元测试的空实现
     */
    static ProseImpactReleaseHook noop() {
        return new ProseImpactReleaseHook() {
            public List<String> workspaceBlockingItems(Long workId, Long workspaceId) { return List.of(); }
            public ProseImpactModels.WorkspaceImpactSummary workspaceSummary(Long workId, Long workspaceId) {
                return new ProseImpactModels.WorkspaceImpactSummary(0, 0, 0, 0, 0,
                        List.of(), List.of(), List.of());
            }
            public void activateRelease(Long workId, Long releaseId, Long previousReleaseId,
                    Long rollbackTargetReleaseId) { }
        };
    }
}
