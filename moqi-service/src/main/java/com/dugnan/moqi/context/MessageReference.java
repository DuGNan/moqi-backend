package com.dugnan.moqi.context;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 表示章节讨论中用户显式指定的完整消息引用。
 */
public record MessageReference(Long messageId, String role, String content) {
}
