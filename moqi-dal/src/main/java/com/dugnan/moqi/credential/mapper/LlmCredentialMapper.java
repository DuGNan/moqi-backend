package com.dugnan.moqi.credential.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.dugnan.moqi.credential.entity.LlmCredentialEntity;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 提供大模型加密凭据查询、CAS 更新和安全清除能力。
 */
public interface LlmCredentialMapper extends BaseMapper<LlmCredentialEntity> {

    /**
     * 按稳定身份物理删除凭据。
     *
     * @param userId 用户标识
     * @param provider 供应商标识
     * @param credentialType 凭据类型
     * @return 删除行数
     */
    @Delete("""
            DELETE FROM llm_credentials
            WHERE user_id = #{userId}
              AND provider = #{provider}
              AND credential_type = #{credentialType}
            """)
    int hardDeleteByIdentity(
            @Param("userId") String userId,
            @Param("provider") String provider,
            @Param("credentialType") String credentialType);
}
