package com.dugnan.moqi.knowledge.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.entity.BaseEntity;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.service.impl.ReleaseKnowledgeSnapshots.Snapshot;
import com.dugnan.moqi.release.entity.StoryReleaseEntity;
import com.dugnan.moqi.release.mapper.StoryReleaseMapper;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 提供指定发布版本知识内容的只读查询，拒绝跨作品与缺失快照。
 */
@Service
public class ReleaseKnowledgeQuery {
    private final StoryReleaseMapper releases;
    private final ReleaseKnowledgeSnapshots snapshots;
    private final ObjectMapper json;

    public ReleaseKnowledgeQuery(StoryReleaseMapper releases, ReleaseKnowledgeSnapshots snapshots,
            ObjectMapper json) {
        this.releases = releases;
        this.snapshots = snapshots;
        this.json = json;
    }

    /** 快照查询不执行补写或封存，不将 Entity 作为公开契约。 */
    @Transactional(readOnly = true)
    public KnowledgeView get(Long workId, Long releaseId) {
        StoryReleaseEntity release = releases.selectById(releaseId);
        if (release == null || !workId.equals(release.getWorkId()) || !Integer.valueOf(0).equals(release.getDeleted())) {
            throw new BusinessException(ErrorCode.STORY_RELEASE_CONFLICT, "发布版本不存在或不属于当前作品");
        }
        Snapshot snapshot = "current".equals(release.getReleaseStatus())
                ? snapshots.current(workId) : snapshots.load(workId, releaseId);
        List<KnowledgeItem> items = new ArrayList<>();
        append(items, "setting", snapshot.settings());
        append(items, "foreshadowing", snapshot.foreshadowing());
        append(items, "chapter_summary", snapshot.summaries());
        append(items, "key_event", snapshot.events());
        return new KnowledgeView(releaseId, List.copyOf(items));
    }

    private void append(List<KnowledgeItem> items, String type, List<? extends BaseEntity> entities) {
        for (BaseEntity entity : entities) {
            Map<String, Object> payload = new LinkedHashMap<>(json.convertValue(entity, new TypeReference<>() { }));
            List.of("id", "deleted", "version", "gmtCreate", "gmtModified", "workId").forEach(payload::remove);
            payload.values().removeIf(java.util.Objects::isNull);
            items.add(new KnowledgeItem(type, entity.getId(), Map.copyOf(payload)));
        }
    }

    /** 稳定发布知识查询契约，content 字段保留各知识类型的业务属性。 */
    public record KnowledgeItem(String type, Long id, Map<String, Object> content) {
    }

    /** 显式区分完整空快照与不可恢复的缺失快照。 */
    public record KnowledgeView(Long releaseId, List<KnowledgeItem> items) {
    }
}
