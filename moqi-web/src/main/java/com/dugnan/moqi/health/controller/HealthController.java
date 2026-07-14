package com.dugnan.moqi.health.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.health.service.HealthQueryService;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供应用健康检查 HTTP 接口。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthQueryService healthQueryService;

    /**
     * 创建健康检查控制器。
     *
     * @param healthQueryService 健康状态查询服务
     */
    public HealthController(HealthQueryService healthQueryService) {
        this.healthQueryService = healthQueryService;
    }

    /**
     * 返回应用健康状态。
     *
     * @return 统一健康状态响应
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(healthQueryService.currentHealth());
    }
}
