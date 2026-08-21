package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.chapter.entity.ChapterProseWorkspaceSelectionEntity;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 提供章节正文工作区选择记录的版本化写入能力。
 */
public interface ChapterProseWorkspaceSelectionMapper extends BaseMapper<ChapterProseWorkspaceSelectionEntity> {

    /**
     * 按版本保存章节最后一次明确选择。
     *
     * @param chapterId 章节 ID
     * @param objectKind 对象类型
     * @param objectId 稳定对象 ID
     * @param baseVersion 客户端基准版本
     * @return 更新行数
     */
    @Update("""
            UPDATE chapter_prose_workspace_selections
            SET selected_object_kind = #{objectKind},
                selected_object_id = #{objectId},
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE chapter_id = #{chapterId}
              AND version = #{baseVersion}
              AND deleted = 0
            """)
    int updateSelectionIfVersion(
            @Param("chapterId") Long chapterId,
            @Param("objectKind") String objectKind,
            @Param("objectId") String objectId,
            @Param("baseVersion") Integer baseVersion);
}
