package com.dugnan.moqi.chapter.service.impl;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.service.ProseCandidateAdoptionService;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 在服务启动后幂等恢复已提交但尚未启动影响分析的候选采纳。
 */
@Component
public class ProseCandidateAdoptionRecoveryRunner implements ApplicationRunner {
    private final ProseCandidateAdoptionService adoptionService;

    public ProseCandidateAdoptionRecoveryRunner(ProseCandidateAdoptionService adoptionService) {
        this.adoptionService = adoptionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        adoptionService.resumePendingImpacts();
    }
}
