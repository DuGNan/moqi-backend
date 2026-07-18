package com.dugnan.moqi.work.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.work.entity.ChapterEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供作品章节的数据访问能力。
 */
public interface ChapterMapper extends BaseMapper<ChapterEntity> {

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
              AND deleted = 0
            """)
    int updateContentIfVersion(
            @Param("chapterId") Long chapterId,
            @Param("content") String content,
            @Param("baseVersion") Integer baseVersion);
}
