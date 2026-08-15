package com.dugnan.moqi.work.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.work.entity.ChapterEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供作品章节的数据访问能力。
 */
public interface ChapterMapper extends BaseMapper<ChapterEntity> {

    /**
     * 锁定未删除章节行以串行化同一章节的短事务任务创建。
     *
     * @param chapterId 章节 ID
     * @return 已锁定章节，不存在时返回 null
     */
    @Select("""
            SELECT *
            FROM chapters
            WHERE id = #{chapterId}
              AND deleted = 0
            FOR UPDATE
            """)
    ChapterEntity selectByIdForUpdate(@Param("chapterId") Long chapterId);

    /**
     * 按正文版本条件更新章节内容。
     *
     * @param chapterId 章节 ID
     * @param content 新正文
     * @param baseVersion 客户端基准版本
     * @return 更新行数
     */
    @Update("""
            UPDATE chapters
            SET content = #{content},
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{chapterId}
              AND version = #{baseVersion}
              AND current_prose_revision_id IS NULL
              AND deleted = 0
            """)
    int updateContentIfVersion(
            @Param("chapterId") Long chapterId,
            @Param("content") String content,
            @Param("baseVersion") Integer baseVersion);

    /**
     * 原子切换章节当前发布正文指针及其兼容读取内容。
     *
     * @param chapterId 章节 ID
     * @param proseRevisionId 新正文 revision ID
     * @param content 新发布正文
     * @param baseVersion 章节乐观锁版本
     * @param expectedCurrentRevisionId 预期当前正文 revision ID
     * @return 更新行数
     */
    @Update("""
            UPDATE chapters
            SET current_prose_revision_id = #{proseRevisionId},
                content = #{content},
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{chapterId}
              AND version = #{baseVersion}
              AND deleted = 0
              AND ((#{expectedCurrentRevisionId} IS NULL AND current_prose_revision_id IS NULL)
                   OR current_prose_revision_id = #{expectedCurrentRevisionId})
            """)
    int updatePublishedRevisionIfVersion(
            @Param("chapterId") Long chapterId,
            @Param("proseRevisionId") Long proseRevisionId,
            @Param("content") String content,
            @Param("baseVersion") Integer baseVersion,
            @Param("expectedCurrentRevisionId") Long expectedCurrentRevisionId);

    /**
     * 将目标 Story Release 完整快照中缺席的章节从公开正文下线。
     *
     * @param chapterId 章节 ID
     * @param baseVersion 章节乐观锁版本
     * @param expectedCurrentRevisionId 预期当前正文 revision ID
     * @return 更新行数
     */
    @Update("""
            UPDATE chapters
            SET current_prose_revision_id = NULL,
                content = NULL,
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{chapterId}
              AND version = #{baseVersion}
              AND deleted = 0
              AND current_prose_revision_id = #{expectedCurrentRevisionId}
            """)
    int clearPublishedRevisionIfVersion(
            @Param("chapterId") Long chapterId,
            @Param("baseVersion") Integer baseVersion,
            @Param("expectedCurrentRevisionId") Long expectedCurrentRevisionId);

    /** 按版本条件更新章节标题。 */
    @Update("""
            UPDATE chapters
            SET title = #{title}, version = version + 1, gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{chapterId} AND version = #{baseVersion} AND deleted = 0
            """)
    int updateTitleIfVersion(
            @Param("chapterId") Long chapterId,
            @Param("title") String title,
            @Param("baseVersion") Integer baseVersion);

    /** 按版本条件逻辑删除章节。 */
    @Update("""
            UPDATE chapters
            SET deleted = 1, version = version + 1, gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{chapterId} AND version = #{baseVersion} AND deleted = 0
            """)
    int softDeleteIfVersion(
            @Param("chapterId") Long chapterId,
            @Param("baseVersion") Integer baseVersion);

    /** 逻辑删除作品下全部未删除章节。 */
    @Update("""
            UPDATE chapters
            SET deleted = 1, version = version + 1, gmt_modified = CURRENT_TIMESTAMP
            WHERE work_id = #{workId} AND deleted = 0
            """)
    int softDeleteActiveByWorkId(@Param("workId") Long workId);
}
