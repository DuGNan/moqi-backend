package com.dugnan.moqi.chapter.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dugnan.moqi.chapter.entity.ReplyPolicyPreferenceEntity;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 提供回复策略偏好的持久化访问。
 */
public interface ReplyPolicyPreferenceMapper extends BaseMapper<ReplyPolicyPreferenceEntity> {

    /**
     * 读取逻辑删除的偏好并锁定，绕过全局逻辑删除条件。
     *
     * @param userId 用户 ID
     * @param scopeType 作用域类型
     * @param scopeId 作用域 ID
     * @return 最近一条已逻辑删除的偏好，不存在时返回 {@code null}
     */
    @Select("""
            SELECT * FROM reply_policy_preferences
            WHERE user_id = #{userId} AND scope_type = #{scopeType}
              AND scope_id = #{scopeId} AND deleted = 1
            ORDER BY id DESC LIMIT 1 FOR UPDATE
            """)
    ReplyPolicyPreferenceEntity selectDeletedForUpdate(
            @Param("userId") String userId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 按服务端持有的版本复活已删除偏好。
     *
     * @param id 偏好 ID
     * @param userId 用户 ID
     * @param replyDepth 回复深度
     * @param expectedVersion 服务端读取到的墓碑版本
     * @return 更新行数
     */
    @Update("""
            UPDATE reply_policy_preferences
            SET deleted = 0, reply_depth = #{replyDepth}, version = version + 1
            WHERE id = #{id} AND user_id = #{userId}
              AND deleted = 1 AND version = #{expectedVersion}
            """)
    int reactivateDeleted(
            @Param("id") Long id,
            @Param("userId") String userId,
            @Param("replyDepth") String replyDepth,
            @Param("expectedVersion") Integer expectedVersion);

    /**
     * 清除同一作用域的旧软删除墓碑，避免当前记录再次软删除时命中唯一键。
     *
     * @param userId 用户 ID
     * @param scopeType 作用域类型
     * @param scopeId 作用域 ID
     * @return 删除行数
     */
    @Delete("""
            DELETE FROM reply_policy_preferences
            WHERE user_id = #{userId} AND scope_type = #{scopeType}
              AND scope_id = #{scopeId} AND deleted = 1
            """)
    int deleteDeletedByScope(
            @Param("userId") String userId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);
}
