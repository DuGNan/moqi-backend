package com.dugnan.moqi.work.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ChapterOutlineQueryMapper extends BaseMapper<ChapterOutlineEntity> {
    @Select("SELECT * FROM chapter_outlines WHERE chapter_id = #{chapterId} AND deleted = 0 ORDER BY revision DESC, id DESC LIMIT 1")
    ChapterOutlineEntity findLatest(@Param("chapterId") Long chapterId);
}
