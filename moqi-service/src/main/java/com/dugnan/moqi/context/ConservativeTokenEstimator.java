package com.dugnan.moqi.context;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 面向中英文混合故事文本的确定性保守 token 估算器。
 *
 * @author dgn
 */
@Component
public class ConservativeTokenEstimator implements TokenEstimator {

    private static final int MIN_TOKEN_COUNT = 1;
    private static final int MIN_TRUNCATION_CHARACTERS = 2;

    @Override
    public int estimate(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isAsciiWord(codePoint)) {
                asciiRun++;
                continue;
            }
            if (asciiRun > 0) {
                tokens += (asciiRun + 3) / 4;
                asciiRun = 0;
            }
            tokens += isCjk(codePoint) ? 1 : 1;
        }
        if (asciiRun > 0) {
            tokens += (asciiRun + 3) / 4;
        }
        return Math.max(MIN_TOKEN_COUNT, tokens);
    }

    @Override
    public String truncate(String text, int maxTokens) {
        if (!StringUtils.hasText(text) || maxTokens <= 0) {
            return "";
        }
        if (estimate(text) <= maxTokens) {
            return text;
        }
        String marker = "\n[…内容已按上下文预算裁剪…]\n";
        if (maxTokens <= estimate(marker) + MIN_TRUNCATION_CHARACTERS) {
            return text.substring(0, Math.min(maxTokens, text.length()));
        }
        int available = Math.max(MIN_TRUNCATION_CHARACTERS, maxTokens - estimate(marker));
        int headCharacters = available / 2;
        int tailCharacters = available - headCharacters;
        while (headCharacters > 1 && tailCharacters > 1) {
            String result = text.substring(0, Math.min(headCharacters, text.length()))
                    + marker
                    + text.substring(Math.max(0, text.length() - Math.min(tailCharacters, text.length())));
            if (estimate(result) <= maxTokens) {
                return result;
            }
            tailCharacters--;
            headCharacters--;
        }
        return text.substring(0, Math.min(maxTokens, text.length()));
    }

    private boolean isAsciiWord(int codePoint) {
        return codePoint < 128 && (Character.isLetterOrDigit(codePoint) || codePoint == '_');
    }

    private boolean isCjk(int codePoint) {
        return (codePoint >= 0x2E80 && codePoint <= 0x9FFF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF);
    }
}
