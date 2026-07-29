package com.dugnan.moqi.spike.graph;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig.node_async;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;

/**
 * Issue #42 的隔离验证适配器，仅位于测试源码，不参与生产装配。
 *
 * @author DuGN
 * @date 2026-07-29
 * @description 验证 Graph 编排、人工中断恢复与现有 LLM Provider V2 的适配边界。
 */
final class GraphRuntimeSpike {

    static final String LOAD_CONTEXT = "loadContext";
    static final String DRAFT = "draft";
    static final String REVIEW = "review";
    static final String COMPLETE = "complete";
    private static final String TASK_ID = "taskId";
    private static final String RUN_ID = "runId";
    private static final String CONTEXT = "context";
    private static final String PROMPT = "prompt";
    private static final String DRAFT_TEXT = "draftText";
    private static final String REVIEW_RESULT = "reviewResult";
    private static final String STATUS = "status";

    private final LlmProvider provider;
    private final CompiledGraph graph;
    private final ConcurrentMap<String, LlmStreamCall> activeCalls = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Consumer<SpikeEvent>> eventConsumers = new ConcurrentHashMap<>();

    GraphRuntimeSpike(LlmProvider provider, BaseCheckpointSaver saver) throws GraphStateException {
        this.provider = Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(saver, "saver");
        this.graph = createGraph(saver);
    }

    SpikeExecution start(SpikeRun run, Consumer<SpikeEvent> consumer) {
        Objects.requireNonNull(run, "run");
        return execute(
                Map.of(
                        TASK_ID, run.taskId(),
                        RUN_ID, run.runId(),
                        PROMPT, run.prompt()),
                config(run),
                consumer);
    }

    SpikeExecution resume(SpikeRun run, Consumer<SpikeEvent> consumer) {
        Objects.requireNonNull(run, "run");
        RunnableConfig config = config(run);
        graph.lastStateOf(config).ifPresent(snapshot -> {
            if (END.equals(snapshot.next())) {
                throw new IllegalStateException("run 已完成，禁止重复 resume");
            }
        });
        return execute(null, config.withResume(), consumer);
    }

    boolean cancel(SpikeRun run) {
        LlmStreamCall call = activeCalls.get(run.threadId());
        return call != null && call.cancel();
    }

    Optional<Map<String, Object>> restoredState(SpikeRun run) {
        return graph.lastStateOf(config(run)).map(snapshot -> snapshot.state().data());
    }

    private SpikeExecution execute(
            Map<String, Object> inputs,
            RunnableConfig config,
            Consumer<SpikeEvent> consumer) {
        Consumer<SpikeEvent> safeConsumer = consumer == null ? ignored -> { } : consumer;
        String threadId = config.threadId().orElseThrow(() ->
                new IllegalStateException("Graph threadId 缺失"));
        eventConsumers.put(threadId, safeConsumer);
        try {
            List<NodeOutput> outputs = graph.stream(inputs, config)
                    .doOnNext(output -> safeConsumer.accept(new SpikeEvent.NodeTransition(output.node())))
                    .collectList()
                    .blockOptional()
                    .orElseGet(List::of);
            boolean interrupted = outputs.stream().anyMatch(InterruptionMetadata.class::isInstance);
            Map<String, Object> state = outputs.isEmpty()
                    ? Map.of()
                    : Map.copyOf(outputs.get(outputs.size() - 1).state().data());
            return new SpikeExecution(
                    interrupted ? SpikeStatus.INTERRUPTED : SpikeStatus.COMPLETED,
                    outputs.stream().map(NodeOutput::node).toList(),
                    state);
        } finally {
            eventConsumers.remove(threadId, safeConsumer);
        }
    }

    private CompiledGraph createGraph(BaseCheckpointSaver saver) throws GraphStateException {
        KeyStrategyFactory strategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(TASK_ID, (previous, current) -> current);
            strategies.put(RUN_ID, (previous, current) -> current);
            strategies.put(CONTEXT, (previous, current) -> current);
            strategies.put(PROMPT, (previous, current) -> current);
            strategies.put(DRAFT_TEXT, (previous, current) -> current);
            strategies.put(REVIEW_RESULT, (previous, current) -> current);
            strategies.put(STATUS, (previous, current) -> current);
            return strategies;
        };
        StateGraph stateGraph = new StateGraph(strategyFactory)
                .addNode(LOAD_CONTEXT, node_async((state, config) -> Map.of(
                        CONTEXT, "task:" + requireState(state.value(TASK_ID), TASK_ID))))
                .addNode(DRAFT, node_async((state, config) -> draft(state.value(PROMPT), config)))
                .addNode(REVIEW, node_async((state, config) -> Map.of(
                        REVIEW_RESULT, "approved-for-human-confirmation")))
                .addNode(COMPLETE, node_async((state, config) -> Map.of(STATUS, "completed")))
                .addEdge(START, LOAD_CONTEXT)
                .addEdge(LOAD_CONTEXT, DRAFT)
                .addEdge(DRAFT, REVIEW)
                .addEdge(REVIEW, COMPLETE)
                .addEdge(COMPLETE, END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptAfter(REVIEW)
                .releaseThread(false)
                .build());
    }

    private Map<String, Object> draft(Optional<Object> promptValue, RunnableConfig config) {
        String prompt = requireState(promptValue, PROMPT);
        LlmRequest request = new LlmRequest(
                List.of(
                        new LlmMessage(LlmRole.SYSTEM, "你是小说草稿生成器。"),
                        new LlmMessage(LlmRole.USER, prompt)),
                LlmOptions.defaults());
        List<String> chunks = new ArrayList<>();
        String threadId = config.threadId().orElseThrow(() ->
                new IllegalStateException("Graph threadId 缺失"));
        LlmStreamCall call = provider.stream(request, event -> collectEvent(threadId, event, chunks));
        activeCalls.put(threadId, call);
        try {
            LlmStreamResult result = call.await();
            if (result.status() == LlmStreamStatus.CANCELED) {
                throw new CancellationException("Provider 调用已取消");
            }
            if (result.status() == LlmStreamStatus.FAILED) {
                throw new LlmProviderException(result.error());
            }
            if (result.status() != LlmStreamStatus.COMPLETED) {
                throw new IllegalStateException("Provider 未进入终态");
            }
            return Map.of(DRAFT_TEXT, String.join("", chunks));
        } finally {
            activeCalls.remove(threadId, call);
        }
    }

    private void collectEvent(String threadId, LlmStreamEvent event, List<String> chunks) {
        if (event instanceof LlmStreamEvent.TextDelta delta) {
            chunks.add(delta.text());
            eventConsumers.getOrDefault(threadId, ignored -> { })
                    .accept(new SpikeEvent.TextDelta(delta.text()));
        }
    }

    private RunnableConfig config(SpikeRun run) {
        return RunnableConfig.builder().threadId(run.threadId()).build();
    }

    private String requireState(Optional<Object> value, String key) {
        return value.map(Object::toString).orElseThrow(() ->
                new IllegalStateException("Graph state 缺少 " + key));
    }

    record SpikeRun(long taskId, long runId, String prompt) {

        SpikeRun {
            if (taskId <= 0 || runId <= 0) {
                throw new IllegalArgumentException("taskId 和 runId 必须为正数");
            }
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException("prompt 不能为空");
            }
        }

        String threadId() {
            return "ai-task:" + taskId + ":run:" + runId;
        }
    }

    record SpikeExecution(SpikeStatus status, List<String> nodes, Map<String, Object> state) {
    }

    enum SpikeStatus {
        INTERRUPTED,
        COMPLETED
    }

    sealed interface SpikeEvent {

        record NodeTransition(String node) implements SpikeEvent {
        }

        record TextDelta(String text) implements SpikeEvent {
        }
    }
}
