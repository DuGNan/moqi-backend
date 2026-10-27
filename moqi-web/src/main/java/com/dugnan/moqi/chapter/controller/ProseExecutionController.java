package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dugnan.moqi.chapter.selection.ProseExecutionStateService;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 提供正文澄清跳过和编辑器应用状态回报，不保存正文。
 */
@RestController
@RequestMapping("/api/selection-assistance")
public class ProseExecutionController {
    private final ProseExecutionStateService state;
    private final SelectionAssistanceService assistance;

    public ProseExecutionController(ProseExecutionStateService state, SelectionAssistanceService assistance) {
        this.state = state;
        this.assistance = assistance;
    }

    @PostMapping("/{id}/skip")
    public ApiResponse<SelectionAssistanceModels.View> skip(@PathVariable Long id,
            @RequestBody SelectionAssistanceModels.ExecutionAction action) {
        state.action(id, action, true);
        return ApiResponse.success(assistance.get(id));
    }

    @PostMapping("/{id}/application")
    public ApiResponse<SelectionAssistanceModels.View> application(@PathVariable Long id,
            @RequestBody SelectionAssistanceModels.ExecutionAction action) {
        state.action(id, action, false);
        return ApiResponse.success(assistance.get(id));
    }
}
