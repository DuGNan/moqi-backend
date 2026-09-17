package com.dugnan.moqi.knowledge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.knowledge.service.impl.ReleaseKnowledgeQuery;
import com.dugnan.moqi.knowledge.service.impl.ReleaseKnowledgeQuery.KnowledgeView;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 暴露发布版本的知识快照读取入口。
 */
@RestController
@RequestMapping("/api/works/{workId}/story-revisions/releases")
public class ReleaseKnowledgeController {
    private final ReleaseKnowledgeQuery query;

    public ReleaseKnowledgeController(ReleaseKnowledgeQuery query) {
        this.query = query;
    }

    /** 查询历史知识内容，缺失可靠快照时返回安全冲突。 */
    @GetMapping("/{releaseId}/knowledge")
    public ApiResponse<KnowledgeView> knowledge(@PathVariable Long workId, @PathVariable Long releaseId) {
        return ApiResponse.success(query.get(workId, releaseId));
    }
}
