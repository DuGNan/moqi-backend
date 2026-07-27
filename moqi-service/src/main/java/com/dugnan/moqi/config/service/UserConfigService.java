package com.dugnan.moqi.config.service;

import com.dugnan.moqi.config.dto.UserConfigModels.ModelStatus;
import com.dugnan.moqi.config.dto.UserConfigModels.UpdateUserConfigRequest;
import com.dugnan.moqi.config.dto.UserConfigModels.UserConfigDetail;
import com.dugnan.moqi.config.dto.UserConfigModels.UserConfigSaved;
import com.dugnan.moqi.llm.DeepSeekProviderConfig;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 定义当前用户配置和离线模型状态查询能力。
 */
public interface UserConfigService {

    /**
     * 读取当前用户指定配置。
     *
     * @param configKey 配置键
     * @return 配置详情
     */
    UserConfigDetail getConfig(String configKey);

    /**
     * 按基础版本保存当前用户配置。
     *
     * @param configKey 配置键
     * @param request 保存请求
     * @return 保存结果
     */
    UserConfigSaved updateConfig(String configKey, UpdateUserConfigRequest request);

    /**
     * 读取不触发网络请求的模型配置状态。
     *
     * @return 模型状态
     */
    ModelStatus getModelStatus();

    /**
     * 按当前配置版本测试模型连接并持久化结果。
     *
     * @param baseVersion 配置基础版本
     * @return 测试后的模型状态
     */
    ModelStatus testModelConnection(Integer baseVersion);

    DeepSeekProviderConfig requireAvailableDeepSeekConfig();
}
