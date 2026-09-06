package com.dugnan.moqi.chapter.service;

/**
 * @author dgn
 * @date 2026-09-04
 * @description 冻结正文对象、未保存草稿和创建依据的模型可见上下文。
 */
public interface ProseObjectPromptContextService {

    /**
     * 读取并校验当前正文对象的模型上下文。
     *
     * @param chapterId 章节 ID
     * @param objectId 正文对象 ID
     * @param draft 可选的未保存编辑器草稿
     * @return 冻结上下文
     */
    FrozenProseObjectContext freeze(Long chapterId, String objectId, ProseObjectDraft draft);

    /** 未保存草稿及其对应的已保存基线。 */
    record ProseObjectDraft(Integer baseVersion, String baseContentHash, String content) {
    }

    /** 同时保留内部校验元数据与经过自然语言编译的模型文本。 */
    record FrozenProseObjectContext(
            ProseObjectTargetService.ProseObjectTarget target,
            String modelText) {
    }
}
