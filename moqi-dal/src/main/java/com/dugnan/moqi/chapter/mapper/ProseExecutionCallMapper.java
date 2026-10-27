package com.dugnan.moqi.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dugnan.moqi.chapter.entity.ProseExecutionCallEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 访问正文会话分阶段调用审计。
 */
@Mapper
public interface ProseExecutionCallMapper extends BaseMapper<ProseExecutionCallEntity> {
}
