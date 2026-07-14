package com.dugnan.moqi.health.service;

import java.util.Map;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:定义应用与数据库健康状态查询能力。
 */
public interface HealthQueryService {

    /**
     * 查询应用及其依赖的健康状态。
     *
     * @return 健康状态及明细
     */
    Map<String, Object> currentHealth();
}
