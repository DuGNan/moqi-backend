package com.dugnan.moqi.work.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.work.entity.WorkEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供作品基础数据的访问能力。
 */
public interface WorkMapper extends BaseMapper<WorkEntity> {

    /** 锁定未删除作品行。 */
    @Select("SELECT * FROM works WHERE id = #{workId} AND deleted = 0 FOR UPDATE")
    WorkEntity selectByIdForUpdate(@Param("workId") Long workId);

    /** 按版本条件更新作品标题。 */
    @Update("""
            UPDATE works
            SET title = #{title}, version = version + 1, gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{workId} AND version = #{baseVersion} AND deleted = 0
            """)
    int updateTitleIfVersion(
            @Param("workId") Long workId,
            @Param("title") String title,
            @Param("baseVersion") Integer baseVersion);

    /** 按版本条件逻辑删除作品。 */
    @Update("""
            UPDATE works
            SET deleted = 1, version = version + 1, gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{workId} AND version = #{baseVersion} AND deleted = 0
            """)
    int softDeleteIfVersion(
            @Param("workId") Long workId,
            @Param("baseVersion") Integer baseVersion);

    /**
     * 按乐观锁和当前指针条件切换作品的 Story Release。
     *
     * @param workId 作品 ID
     * @param storyReleaseId 新 Story Release ID
     * @param baseVersion 作品乐观锁版本
     * @param expectedCurrentReleaseId 预期当前 Story Release ID
     * @return 更新行数
     */
    @Update("""
            UPDATE works
            SET current_story_release_id = #{storyReleaseId},
                version = version + 1,
                gmt_modified = CURRENT_TIMESTAMP
            WHERE id = #{workId}
              AND version = #{baseVersion}
              AND deleted = 0
              AND ((#{expectedCurrentReleaseId} IS NULL AND current_story_release_id IS NULL)
                   OR current_story_release_id = #{expectedCurrentReleaseId})
            """)
    int updateCurrentStoryReleaseIfVersion(
            @Param("workId") Long workId,
            @Param("storyReleaseId") Long storyReleaseId,
            @Param("baseVersion") Integer baseVersion,
            @Param("expectedCurrentReleaseId") Long expectedCurrentReleaseId);
}
