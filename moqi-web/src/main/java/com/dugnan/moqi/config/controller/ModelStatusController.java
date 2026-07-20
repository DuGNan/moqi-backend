package com.dugnan.moqi.config.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.config.dto.UserConfigModels.ModelStatus;
import com.dugnan.moqi.config.service.UserConfigService;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 提供不触发网络探测的模型配置状态 HTTP 接口。
 */
@RestController
@RequestMapping("/api/system")
public class ModelStatusController {

    private final UserConfigService userConfigService;

    /**
     * 创建模型状态控制器。
     *
     * @param userConfigService 用户配置服务
     */
    public ModelStatusController(UserConfigService userConfigService) {
        this.userConfigService = userConfigService;
    }

    /**
     * 查询离线模型配置状态。
     *
     * @return 模型状态响应
     */
    @GetMapping("/model-status")
    public ApiResponse<ModelStatus> modelStatus() {
        return ApiResponse.success(userConfigService.getModelStatus());
    }
}
