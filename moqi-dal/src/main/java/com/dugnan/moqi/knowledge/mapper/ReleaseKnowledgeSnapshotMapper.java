package com.dugnan.moqi.knowledge.mapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.UpdateProvider;

import com.dugnan.moqi.common.entity.BaseEntity;
import com.dugnan.moqi.knowledge.entity.ChapterKeyEventEntity;
import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;
import com.dugnan.moqi.knowledge.entity.ForeshadowingItemEntity;
import com.dugnan.moqi.knowledge.entity.ReleaseKnowledgeSnapshotEntity;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 读写发布知识快照。
 */
@Mapper
public interface ReleaseKnowledgeSnapshotMapper extends BaseMapper<ReleaseKnowledgeSnapshotEntity> {
    /**
     * 锁定作品全部设定，为历史内容恢复保留已删除行。
     * @param workId 作品 ID
     * @return 包含软删除记录的设定
     */
    @Select("SELECT * FROM setting_entries WHERE work_id=#{workId} ORDER BY id FOR UPDATE")
    List<SettingEntryEntity> allSettings(@Param("workId") Long workId);

    /**
     * 锁定作品全部伏笔，为历史内容恢复保留已删除行。
     * @param workId 作品 ID
     * @return 包含软删除记录的伏笔
     */
    @Select("SELECT * FROM foreshadowing_items WHERE work_id=#{workId} ORDER BY id FOR UPDATE")
    List<ForeshadowingItemEntity> allForeshadowing(@Param("workId") Long workId);

    /**
     * 锁定作品全部摘要，为历史内容恢复保留已删除行。
     * @param workId 作品 ID
     * @return 包含软删除记录的摘要
     */
    @Select("SELECT * FROM chapter_summaries WHERE work_id=#{workId} ORDER BY id FOR UPDATE")
    List<ChapterSummaryEntity> allSummaries(@Param("workId") Long workId);

    /**
     * 锁定作品全部关键事件，为历史内容恢复保留已删除行。
     * @param workId 作品 ID
     * @return 包含软删除记录的关键事件
     */
    @Select("SELECT * FROM chapter_key_events WHERE work_id=#{workId} ORDER BY id FOR UPDATE")
    List<ChapterKeyEventEntity> allEvents(@Param("workId") Long workId);

    /**
     * 按作品和当前版本恢复投影，显式允许软删除行重新有效。
     * @param row 已校验的历史内容
     * @param workId 作品 ID
     * @param expectedVersion 当前投影版本
     * @return 更新行数
     */
    @UpdateProvider(type = ProjectionSql.class, method = "restore")
    int restoreProjection(@Param("row") BaseEntity row, @Param("workId") Long workId,
            @Param("expectedVersion") Integer expectedVersion);

    /** 只接受四个内部 Entity 的静态元数据，字段值一律使用参数绑定。 */
    final class ProjectionSql {
        public String restore(Map<String, Object> parameters) {
            BaseEntity row = (BaseEntity) parameters.get("row");
            if (!Set.of(SettingEntryEntity.class, ForeshadowingItemEntity.class,
                    ChapterSummaryEntity.class, ChapterKeyEventEntity.class).contains(row.getClass())) {
                throw new IllegalArgumentException("Unsupported release knowledge projection");
            }
            var table = TableInfoHelper.getTableInfo(row.getClass());
            String assignments = table.getFieldList().stream()
                    .filter(field -> !Set.of("version", "gmtCreate", "gmtModified", "workId")
                            .contains(field.getProperty()))
                    .map(field -> field.getColumn() + "=#{row." + field.getProperty() + "}")
                    .collect(Collectors.joining(","));
            return "UPDATE " + table.getTableName() + " SET " + assignments
                    + ",version=#{expectedVersion}+1,gmt_modified=NOW()"
                    + " WHERE id=#{row.id} AND work_id=#{workId} AND version=#{expectedVersion}";
        }
    }
}
