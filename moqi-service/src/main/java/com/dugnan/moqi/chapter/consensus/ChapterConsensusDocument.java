package com.dugnan.moqi.chapter.consensus;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 表示从 Brief 内容读取出的结构化共识或历史文本。
 */
public record ChapterConsensusDocument(
        String contentFormat,
        ChapterConsensusContentV1 consensus,
        String legacyText) {

    /** 结构化共识 V1 格式标识。 */
    public static final String STRUCTURED_V1 = "structured_v1";

    /** 历史自由文本格式标识。 */
    public static final String LEGACY_TEXT = "legacy_text";

    /**
     * 创建结构化共识文档。
     *
     * @param consensus 结构化共识
     * @return 结构化共识文档
     */
    public static ChapterConsensusDocument structured(ChapterConsensusContentV1 consensus) {
        return new ChapterConsensusDocument(STRUCTURED_V1, consensus, null);
    }

    /**
     * 创建历史文本共识文档。
     *
     * @param legacyText 历史文本
     * @return 历史文本共识文档
     */
    public static ChapterConsensusDocument legacy(String legacyText) {
        return new ChapterConsensusDocument(LEGACY_TEXT, null, legacyText);
    }
}
