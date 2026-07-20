package com.dugnan.moqi.config.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.config.dto.UserConfigModels.UpdateUserConfigRequest;
import com.dugnan.moqi.config.dto.UserConfigModels.UserConfigDetail;
import com.dugnan.moqi.config.dto.UserConfigModels.UserConfigSaved;
import com.dugnan.moqi.config.service.UserConfigService;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 提供当前本地用户配置读取与保存 HTTP 接口。
 */
@RestController
@RequestMapping("/api/user-configs")
public class UserConfigController {

    private final UserConfigService userConfigService;

    /**
     * 创建用户配置控制器。
     *
     * @param userConfigService 用户配置服务
     */
    public UserConfigController(UserConfigService userConfigService) {
        this.userConfigService = userConfigService;
    }

    /**
     * 读取当前用户配置。
     *
     * @param configKey 配置键
     * @return 配置详情响应
     */
    @GetMapping("/{configKey}")
    public ApiResponse<UserConfigDetail> config(@PathVariable String configKey) {
        return ApiResponse.success(userConfigService.getConfig(configKey));
    }

    /**
     * 保存当前用户配置。
     *
     * @param configKey 配置键
     * @param request 保存请求
     * @return 保存结果响应
     */
    @PutMapping("/{configKey}")
    public ApiResponse<UserConfigSaved> saveConfig(
            @PathVariable String configKey,
            @RequestBody UpdateUserConfigRequest request) {
        return ApiResponse.success(userConfigService.updateConfig(configKey, request));
    }
}
