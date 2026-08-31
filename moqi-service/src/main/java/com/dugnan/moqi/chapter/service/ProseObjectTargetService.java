package com.dugnan.moqi.chapter.service;

/**
 * @author dgn
 * @date 2026-08-26
 * @description 解析并冻结正文对象会话使用的服务端权威目标。
 */
public interface ProseObjectTargetService {

    /**
     * 校验正文对象属于章节并读取当前保存版本。
     *
     * @param chapterId 章节 ID
     * @param objectId 稳定正文对象 ID
     * @return 服务端权威正文对象
     */
    ProseObjectTarget resolve(Long chapterId, String objectId);

    /** 正文对象在模型任务创建时冻结的安全输入。 */
    record ProseObjectTarget(
            String objectId,
            String objectKind,
            Integer version,
            String contentHash,
            String content,
            String sourceDescription) {

        /** 生成不暴露内部标识的模型可见当前目标说明。 */
        public String promptText() {
            String label = "formal".equals(objectKind) ? "正式正文" : "正文候选";
            return "当前讨论对象：" + label
                    + "\n当前保存版本：" + version
                    + "\n当前内容 hash：" + contentHash
                    + "\n来源说明：" + sourceDescription
                    + "\n当前正文：\n" + content;
        }
    }
}
