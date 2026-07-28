package com.dugnan.moqi.context;

/**
 * Story Context Engine V1 支持的任务画像。
 *
 * @author dgn
 */
public enum StoryContextProfile {
    /** 章节共创讨论。 */
    CHAPTER_DISCUSSION(4096, 15, 30, 20, 35),
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

    public int defaultOutputReserveTokens() {
        return defaultOutputReserveTokens;
    }

    public int structurePercent() {
        return structurePercent;
    }

    public int knowledgePercent() {
        return knowledgePercent;
    }

    public int currentTextPercent() {
        return currentTextPercent;
    }

    public int historyPercent() {
        return historyPercent;
    }
}
