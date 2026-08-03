package com.dugnan.moqi.llm.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 映射用于模型调用成本估算的版本化单价配置。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("llm_model_prices")
public class LlmModelPriceEntity extends BaseEntity {

    private String provider;
    private String model;
    private String currency;
    private BigDecimal inputCacheHitPricePerMillion;
    private BigDecimal inputCacheMissPricePerMillion;
    private BigDecimal outputPricePerMillion;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String sourceReference;
}
