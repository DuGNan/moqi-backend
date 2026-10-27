package com.dugnan.moqi.chapter.selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 编解码一次性正文引用，并在冻结全文中精确验证原子修改。
 */
public final class ProseConversationProtocol {
    private static final int MAX_EDITS = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LEGACY = Pattern.compile(
            "^(正文对话|讨论选区|改写|润色|扩写|压缩)\\s*\\n作者要求：([\\s\\S]*?)\\n引用正文：\\s*\\n?([\\s\\S]+)$");

    private ProseConversationProtocol() {
    }

    public static JsonNode reference(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("正文引用元数据无法读取", exception);
        }
    }

    public static String modelText(ChapterConversationMessageEntity message) {
        JsonNode quote = reference(message.getProseReferenceJson());
        return quote == null ? message.getContent()
                : message.getContent() + "\n\n【作者本轮引用的正文片段，仅用于理解指代】\n" + quote.path("text").asText();
    }

    public static String removeRepeatedLegacyQuote(String content, java.util.Set<String> seen) {
        var match = LEGACY.matcher(content);
        if (!match.matches()) {
            return content;
        }
        return match.group(2) + (seen.add(match.group(3))
                ? "\n\n【当时引用的正文】\n" + match.group(3) : "");
    }

    public static String apply(String source, JsonNode edits) {
        if (edits == null || !edits.isArray() || edits.isEmpty() || edits.size() > MAX_EDITS) {
            throw new IllegalArgumentException("修改结果需要一至二十处明确的原文和新文");
        }
        List<Patch> patches = new ArrayList<>();
        for (JsonNode edit : edits) {
            if (!edit.isObject() || edit.size() != 2 || !edit.path("original").isTextual()
                    || !edit.path("replacement").isTextual()) {
                throw new IllegalArgumentException("修改结果缺少原文或新文");
            }
            String original = edit.get("original").textValue();
            int start = source.indexOf(original);
            boolean invalidInsertion = original.isEmpty() && (!source.isEmpty() || edits.size() != 1);
            boolean repeated = !original.isEmpty() && source.indexOf(original, start + 1) >= 0;
            if (invalidInsertion || start < 0 || repeated) {
                String reason = invalidInsertion ? "空原文不能用于非空正文插入"
                        : start < 0 ? "原文不存在于当前编辑器，请逐字复制当前稿而不是聊天试写"
                        : "原文重复，请补充足够相邻原文以唯一定位";
                throw new IllegalArgumentException("第" + (patches.size() + 1) + "处修改原文无法唯一定位：" + reason);
            }
            patches.add(new Patch(start, start + original.length(), edit.get("replacement").textValue()));
        }
        patches.sort(Comparator.comparingInt(Patch::start));
        int end = -1;
        for (Patch patch : patches) {
            if (patch.start() < end) {
                throw new IllegalArgumentException("修改范围重叠，请明确需要修改的段落");
            }
            end = patch.end();
        }
        StringBuilder result = new StringBuilder(source);
        for (int index = patches.size() - 1; index >= 0; index--) {
            Patch patch = patches.get(index);
            result.replace(patch.start(), patch.end(), patch.replacement());
        }
        return result.toString();
    }

    private record Patch(int start, int end, String replacement) {
    }
}
