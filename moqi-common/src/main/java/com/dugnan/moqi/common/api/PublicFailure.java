package com.dugnan.moqi.common.api;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 定义作者界面可安全消费的统一失败事实。
 */
public record PublicFailure(
        String code,
        String category,
        boolean retryable,
        String diagnosticRef) {
}
