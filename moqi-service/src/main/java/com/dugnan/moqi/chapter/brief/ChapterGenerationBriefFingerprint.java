package com.dugnan.moqi.chapter.brief;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 对规范化章节正文生成说明来源与模板版本计算稳定 SHA-256 指纹。
 */
@Component
public class ChapterGenerationBriefFingerprint {

    private final ObjectMapper canonicalMapper;

    public ChapterGenerationBriefFingerprint(ObjectMapper objectMapper) {
        canonicalMapper = objectMapper.copy();
    }

    public String calculate(String templateVersion, ChapterGenerationBriefSource source) {
        try {
            String canonical = canonicalMapper.writeValueAsString(new FingerprintInput(templateVersion, source));
            String normalized = Normalizer.normalize(canonical.replace("\r\n", "\n"), Normalizer.Form.NFC);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "章节正文生成说明来源无法序列化", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SHA-256 不可用", exception);
        }
    }

    private record FingerprintInput(String templateVersion, ChapterGenerationBriefSource source) {
    }
}
