package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-16
 * @description 以不包含模型正文的类别和字段路径描述评价 Finding 安全契约失败。
 */
public class EvaluationFindingContractException extends BusinessException {
    private final String category;
    private final String path;

    public EvaluationFindingContractException(String category, String path) {
        super(ErrorCode.BAD_REQUEST, "模型评价 Finding 不符合安全契约");
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
