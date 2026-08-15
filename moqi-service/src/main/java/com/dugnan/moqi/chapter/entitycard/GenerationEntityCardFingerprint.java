package com.dugnan.moqi.chapter.entitycard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.SourceRef;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 对实体卡模板、规范化卡片与固定来源计算稳定 SHA-256 指纹。
 */
@Component
public class GenerationEntityCardFingerprint {

    private final ObjectMapper canonicalMapper;

    public GenerationEntityCardFingerprint(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy();
    }

    public String calculate(String templateVersion, List<GenerationEntityCard> cards, List<SourceRef> sourceRefs) {
        try {
            String canonical = canonicalMapper.writeValueAsString(
                    new FingerprintInput(templateVersion, cards, sourceRefs));
            String normalized = Normalizer.normalize(canonical.replace("\r\n", "\n"), Normalizer.Form.NFC);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "章节实体卡无法序列化", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SHA-256 不可用", exception);
        }
    }

    private record FingerprintInput(
            String templateVersion,
            List<GenerationEntityCard> cards,
            List<SourceRef> sourceRefs) {
    }
}
