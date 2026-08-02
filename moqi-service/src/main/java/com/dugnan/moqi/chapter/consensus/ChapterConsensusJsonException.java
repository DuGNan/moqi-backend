package com.dugnan.moqi.chapter.consensus;

/**
 * @author dgn
 * @date 2026-08-02
 * @description 表示章节共识 Provider JSON 缺少字段或字段类型不符合契约。
 */
public class ChapterConsensusJsonException extends RuntimeException {

    /**
     * 创建固定安全摘要的 JSON 契约异常。
     */
    public ChapterConsensusJsonException() {
        super("模型共识 JSON 不符合字段契约");
    }

    /**
     * 创建保留解析原因但不暴露原始响应的 JSON 契约异常。
     *
     * @param cause JSON 映射失败原因
     */
    public ChapterConsensusJsonException(Throwable cause) {
        super("模型共识 JSON 不符合字段契约", cause);
    }
}
