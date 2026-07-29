package com.dugnan.moqi.llm;

import java.util.List;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 定义供应商无关的模型生成选项。
 */
public record LlmOptions(
        Integer maxOutputTokens,
        Double temperature,
        List<String> stopSequences,
        LlmResponseFormat responseFormat) {

    private static final double MIN_TEMPERATURE = 0D;
    private static final double MAX_TEMPERATURE = 2D;

    /**
     * 固化停止序列并校验供应商无关选项。
     *
     * @param maxOutputTokens 最大输出 token 数
     * @param temperature 采样温度
     * @param stopSequences 停止序列
     * @param responseFormat 响应格式
     * @throws IllegalArgumentException 输出上限或温度范围不合法
     */
    public LlmOptions {
        stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
        responseFormat = responseFormat == null ? LlmResponseFormat.TEXT : responseFormat;
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens 必须为正整数");
        }
        if (temperature != null && isTemperatureOutOfRange(temperature)) {
            throw new IllegalArgumentException("temperature 必须在 0 到 2 之间");
        }
    }

    private static boolean isTemperatureOutOfRange(double temperature) {
        return temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE;
    }

    /**
     * 创建文本响应的默认生成选项。
     *
     * @return 默认生成选项
     */
    public static LlmOptions defaults() {
        return new LlmOptions(null, null, List.of(), LlmResponseFormat.TEXT);
    }
}
