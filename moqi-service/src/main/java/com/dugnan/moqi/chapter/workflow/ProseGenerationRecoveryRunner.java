package com.dugnan.moqi.chapter.workflow;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.event.ChapterGenerationCompletedEvent;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;

/**
 * @author dgn
 * @date 2026-10-20
 * @description 有界恢复已提交生成输出的对象写回与候选评价，不重复调用模型生成。
 */
@Component
public class ProseGenerationRecoveryRunner implements ApplicationRunner {

    private final ChapterGenerationMapper generationMapper;
    private final ProseCandidateGenerationCompletedListener completionListener;

    public ProseGenerationRecoveryRunner(ChapterGenerationMapper generationMapper,
            ProseCandidateGenerationCompletedListener completionListener) {
        this.generationMapper = generationMapper;
        this.completionListener = completionListener;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        recover();
    }

    @Scheduled(fixedDelayString = "${moqi.prose-generation.recovery-delay-ms:30000}")
    public void recover() {
        generationMapper.findPendingTargetWritebacks().forEach(generation ->
                completionListener.handle(new ChapterGenerationCompletedEvent(
                        generation.getChapterId(), generation.getId())));
    }
}
