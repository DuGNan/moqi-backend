package com.dugnan.moqi.chapter.policy;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 定义章节讨论单轮回复模式。
 */
public enum ReplyMode {
    /** 澄清高影响歧义。 */
    CLARIFY,
    /** 比较少量候选。 */
    COMPARE,
    /** 收束已确认内容与待决项。 */
    CONVERGE,
    /** 输出明确请求的结构化规划。 */
    PLAN,
    /** 输出明确请求的正文草稿。 */
    DRAFT;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ReplyMode from(String value) {
        return value == null ? null : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
