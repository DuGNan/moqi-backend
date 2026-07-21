package com.dugnan.moqi.llm;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 在读取响应体前按 HTTP 状态映射安全错误。
 */
final class DeepSeekResponseErrorHandler implements ResponseErrorHandler {

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 401 || status == 403) {
            throw new LlmProviderException(LlmProviderError.AUTHENTICATION);
        }
        if (status == 429) {
            throw new LlmProviderException(LlmProviderError.RATE_LIMITED);
        }
        if (status >= 500) {
            throw new LlmProviderException(LlmProviderError.SERVICE_UNAVAILABLE);
        }
        throw new LlmProviderException(LlmProviderError.REQUEST_REJECTED);
    }
}
