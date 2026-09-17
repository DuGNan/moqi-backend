package com.dugnan.moqi.knowledge.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.ReflectionKit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.entity.BaseEntity;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.entity.ChapterKeyEventEntity;
import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;
import com.dugnan.moqi.knowledge.entity.ForeshadowingItemEntity;
import com.dugnan.moqi.knowledge.entity.ReleaseKnowledgeSnapshotEntity;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.ReleaseKnowledgeSnapshotMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 在发布事务内封存知识内容并恢复历史版本，当前知识表作为有效版本投影。
 */
@Service
public class ReleaseKnowledgeSnapshots {
    private final ReleaseKnowledgeSnapshotMapper snapshots;
    private final SettingEntryMapper settings;
    private final ForeshadowingItemMapper foreshadowing;
    private final ChapterSummaryMapper summaries;
    private final ChapterKeyEventMapper events;
    private final ObjectMapper json;

    public ReleaseKnowledgeSnapshots(ReleaseKnowledgeSnapshotMapper snapshots,
            SettingEntryMapper settings, ForeshadowingItemMapper foreshadowing,
            ChapterSummaryMapper summaries, ChapterKeyEventMapper events, ObjectMapper json) {
        this.snapshots = snapshots;
        this.settings = settings;
        this.foreshadowing = foreshadowing;
        this.summaries = summaries;
        this.events = events;
        this.json = json;
    }

    /** 当前版本离开前封存最终内容；历史快照不能覆盖。调用者持有作品锁。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = RuntimeException.class)
    public void capture(Long workId, Long releaseId, boolean sealed) {
        if (releaseId == null) {
            return;
        }
        Snapshot contents = new Snapshot(read(settings, workId), read(foreshadowing, workId),
                read(summaries, workId), read(events, workId));
        ReleaseKnowledgeSnapshotEntity previous = find(workId, releaseId);
        if (previous != null && Integer.valueOf(1).equals(previous.getSealed())) {
            throw conflict("历史知识快照已封存");
        }
        String encoded;
        try {
            encoded = json.writeValueAsString(contents);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "知识快照无法保存", exception);
        }
        if (previous == null) {
            ReleaseKnowledgeSnapshotEntity snapshot = new ReleaseKnowledgeSnapshotEntity();
            snapshot.setWorkId(workId);
            snapshot.setReleaseId(releaseId);
            snapshot.setSnapshotJson(encoded);
            snapshot.setSealed(sealed ? 1 : 0);
            snapshot.setDeleted(0);
            snapshot.setVersion(0);
            snapshots.insert(snapshot);
        } else if (snapshots.update(null, new UpdateWrapper<ReleaseKnowledgeSnapshotEntity>()
                .eq("id", previous.getId()).eq("version", previous.getVersion()).eq("sealed", 0)
                .set("snapshot_json", encoded).set("sealed", sealed ? 1 : 0)
                .setSql("version = version + 1")) != 1) {
            throw conflict("知识快照发生并发变化");
        }
    }

    /** 读取指定作品的完整快照；缺失历史不能用当前内容伪造。 */
    public Snapshot load(Long workId, Long releaseId) {
        ReleaseKnowledgeSnapshotEntity snapshot = find(workId, releaseId);
        if (snapshot == null) {
            throw conflict("此发布版本缺少可靠知识快照，无法恢复");
        }
        try {
            return json.readValue(snapshot.getSnapshotJson(), Snapshot.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "知识快照无法读取", exception);
        }
    }

    /** 与正文指针切换共享事务，任何失败由外层发布事务整体回滚。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = RuntimeException.class)
    public void restore(Long workId, Long releaseId) {
        Snapshot target = load(workId, releaseId);
        restoreRows(settings, snapshots.allSettings(workId), workId, target.settings());
        restoreRows(foreshadowing, snapshots.allForeshadowing(workId), workId, target.foreshadowing());
        restoreRows(summaries, snapshots.allSummaries(workId), workId, target.summaries());
        restoreRows(events, snapshots.allEvents(workId), workId, target.events());
    }

    private ReleaseKnowledgeSnapshotEntity find(Long workId, Long releaseId) {
        return snapshots.selectOne(new QueryWrapper<ReleaseKnowledgeSnapshotEntity>()
                .eq("work_id", workId).eq("release_id", releaseId).eq("deleted", 0));
    }

    /** 当前版本包含作者正常维护的有效知识；只读查询不补写快照。 */
    public Snapshot current(Long workId) {
        return new Snapshot(readCurrent(settings, workId), readCurrent(foreshadowing, workId),
                readCurrent(summaries, workId), readCurrent(events, workId));
    }

    private <T extends BaseEntity> List<T> readCurrent(BaseMapper<T> mapper, Long workId) {
        return mapper.selectList(new QueryWrapper<T>().eq("work_id", workId).eq("deleted", 0).orderByAsc("id"));
    }

    private <T extends BaseEntity> List<T> read(BaseMapper<T> mapper, Long workId) {
        return mapper.selectList(new QueryWrapper<T>().eq("work_id", workId).eq("deleted", 0)
                .orderByAsc("id").last("FOR UPDATE"));
    }

    private <T extends BaseEntity> void restoreRows(BaseMapper<T> mapper, List<T> allRows,
            Long workId, List<T> target) {
        Map<Long, T> current = allRows.stream()
                .collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        for (T row : target) {
            T live = current.remove(row.getId());
            if (live == null || !Objects.equals(workId, ReflectionKit.getFieldValue(row, "workId"))) {
                throw conflict("知识历史记录缺失或不属于当前作品");
            }
            // 专用 SQL 显式写空字段且允许重新激活软删除行，不受通用逻辑删除过滤影响。
            if (snapshots.restoreProjection(row, workId, live.getVersion()) != 1) {
                throw conflict("知识恢复时发生并发修改");
            }
        }
        for (T row : current.values()) {
            if (Integer.valueOf(0).equals(row.getDeleted())
                    && mapper.update(null, new UpdateWrapper<T>().eq("id", row.getId())
                            .eq("work_id", workId).eq("version", row.getVersion())
                            .set("deleted", 1).set("version", row.getVersion() + 1)) != 1) {
                throw conflict("知识恢复时发生并发修改");
            }
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.STORY_RELEASE_CONFLICT, message);
    }

    /** 仅供领域内部持久化使用，不作为公开 Entity 响应。 */
    public record Snapshot(List<SettingEntryEntity> settings, List<ForeshadowingItemEntity> foreshadowing,
            List<ChapterSummaryEntity> summaries, List<ChapterKeyEventEntity> events) {
    }
}
