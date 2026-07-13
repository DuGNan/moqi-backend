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

    public HealthController(HealthQueryService healthQueryService) {
        this.healthQueryService = healthQueryService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(healthQueryService.currentHealth());
    }
}
