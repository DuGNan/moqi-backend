package com.dugnan.moqi.llm;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.entity.LlmModelCallEntity;
import com.dugnan.moqi.chapter.mapper.LlmModelCallMapper;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 在应用启动时将上一个进程遗留的运行中模型调用收敛为未知终态。
 */
@Component
public class LlmCallRecoveryRunner implements ApplicationRunner {

    private final LlmModelCallMapper callMapper;

    public LlmCallRecoveryRunner(LlmModelCallMapper callMapper) {
        this.callMapper = callMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDateTime recoveryTime = LocalDateTime.now();
        callMapper.update(null, new UpdateWrapper<LlmModelCallEntity>()
                .eq("deleted", 0)
                .eq("call_status", "running")
                .lt("started_at", recoveryTime)
                .set("call_status", "unknown")
                .set("error_category", "process_restart")
                .set("error_code", "PROCESS_RESTART")
                .set("error_message", "模型调用因服务进程重启未能确认最终结果")
                .set("finished_at", recoveryTime));
    }
}
