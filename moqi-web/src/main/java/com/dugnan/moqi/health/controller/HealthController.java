package com.dugnan.moqi.health.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.health.service.HealthQueryService;

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
