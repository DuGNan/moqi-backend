package com.dugnan.moqi.chapter.policy;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 定义面向用户体验的章节讨论回复深度。
 */
public enum ReplyDepth {
    /** 简洁回复。 */
    BRIEF,
    /** 平衡回复。 */
    BALANCED,
    /** 深入回复。 */
    DEEP;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ReplyDepth from(String value) {
        return value == null ? null : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
