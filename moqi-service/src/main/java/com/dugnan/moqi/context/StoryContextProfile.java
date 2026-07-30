package com.dugnan.moqi.context;

/**
 * Story Context Engine V1 支持的任务画像。
 *
 * @author dgn
 */
public enum StoryContextProfile {
    /** 章节共创讨论。 */
    CHAPTER_DISCUSSION(4096, 15, 30, 20, 35),
    /** 章节大纲调整候选生成。 */
    OUTLINE_ADJUSTMENT(4096, 30, 30, 30, 10),
    /** 场景正文生成。 */
    SCENE_GENERATION(8192, 20, 30, 45, 5),
    /** 一致性检查。 */
    CONSISTENCY_REVIEW(4096, 10, 45, 40, 5);

    private final int defaultOutputReserveTokens;
    private final int structurePercent;
    private final int knowledgePercent;
    private final int currentTextPercent;
    private final int historyPercent;

    StoryContextProfile(
            int defaultOutputReserveTokens,
            int structurePercent,
            int knowledgePercent,
            int currentTextPercent,
            int historyPercent) {
        this.defaultOutputReserveTokens = defaultOutputReserveTokens;
        this.structurePercent = structurePercent;
        this.knowledgePercent = knowledgePercent;
        this.currentTextPercent = currentTextPercent;
        this.historyPercent = historyPercent;
    }

    /**
     * 获取默认输出预留 token 数。
     *
     * @return 默认输出预留 token 数
     */
    public int defaultOutputReserveTokens() {
        return defaultOutputReserveTokens;
    }

    /**
     * 获取结构资料预算百分比。
     *
     * @return 结构资料预算百分比
     */
    public int structurePercent() {
        return structurePercent;
    }

    /**
     * 获取知识资料预算百分比。
     *
     * @return 知识资料预算百分比
     */
    public int knowledgePercent() {
        return knowledgePercent;
    }

    /**
     * 获取当前文本预算百分比。
     *
     * @return 当前文本预算百分比
     */
    public int currentTextPercent() {
        return currentTextPercent;
    }

    /**
     * 获取历史对话预算百分比。
     *
     * @return 历史对话预算百分比
     */
    public int historyPercent() {
        return historyPercent;
    }
}
