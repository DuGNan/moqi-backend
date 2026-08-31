package com.dugnan.moqi.context;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 区分上下文条目的权威、候选、待决、已否定与证据语义。
 */
public enum StoryContextAuthorityStatus {
    /** 已经确认或正式生效的权威资料。 */
    CONFIRMED,
    /** 尚未确认的候选。 */
    CANDIDATE,
    /** 仍待用户决定的问题。 */
    PENDING,
    /** 已被用户否定，仅允许保留短墓碑。 */
    REJECTED,
    /** 讨论历史或当前输入证据，不具备事实权威。 */
    EVIDENCE;

    /**
     * 使用稳定的小写值写入快照。
     *
     * @return 小写状态
     */
    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 兼容读取大小写状态以及旧快照缺失值。
     *
     * @param value JSON 状态
     * @return 权威状态
     */
    @JsonCreator
    public static StoryContextAuthorityStatus from(String value) {
        return value == null ? EVIDENCE : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
