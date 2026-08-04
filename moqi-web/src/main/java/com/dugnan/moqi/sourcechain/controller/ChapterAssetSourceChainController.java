package com.dugnan.moqi.sourcechain.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.sourcechain.ChapterAssetSourceChainService;
import com.dugnan.moqi.sourcechain.dto.ChapterAssetSourceChainModels.ChapterAssetSourceChainView;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 提供章节资产来源链和有效性状态的只读查询接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class ChapterAssetSourceChainController {
    private final ChapterAssetSourceChainService sourceChainService;

    public ChapterAssetSourceChainController(ChapterAssetSourceChainService sourceChainService) {
        this.sourceChainService = sourceChainService;
    }

    @GetMapping("/{chapterId}/asset-source-chain")
    public ApiResponse<ChapterAssetSourceChainView> getSourceChain(@PathVariable Long chapterId) {
        return ApiResponse.success(sourceChainService.getSourceChain(chapterId));
    }
}
