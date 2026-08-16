package com.dugnan.moqi.impact;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-16
 * @description 以不包含正文的类别和字段路径描述影响分析安全契约失败。
 */
public class ProseImpactContractException extends BusinessException {
    private final String category;
    private final String path;

    public ProseImpactContractException(String category, String path) {
        super(ErrorCode.PROSE_IMPACT_REPORT_INVALID,
                "影响分析证据输出字段 " + path + " 不符合安全契约：" + category);
        this.category = category;
        this.path = path;
    }

    public String category() {
        return category;
    }

    public String path() {
        return path;
    }
}
