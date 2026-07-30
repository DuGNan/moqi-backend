package com.dugnan.moqi.chapter.outline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.CollectionDiff;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateDiff;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.SceneDiff;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.ValueDiff;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Scene;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 基于稳定场景 ID 生成大纲候选与基础大纲的确定性差异。
 */
@Service
public class OutlineCandidateDiffService {

    /**
     * 计算两个大纲内容的字段和场景差异。
     *
     * @param base 基础大纲
     * @param candidate 候选大纲
     * @return 差异结果
     */
    public OutlineCandidateDiff diff(OutlineCandidateContent base, OutlineCandidateContent candidate) {
        Map<String, IndexedScene> baseScenes = indexed(base.scenes());
        Map<String, IndexedScene> candidateScenes = indexed(candidate.scenes());
        List<SceneDiff> sceneDiffs = new ArrayList<>();
        for (IndexedScene baseScene : baseScenes.values()) {
            IndexedScene current = candidateScenes.get(baseScene.scene().id());
            if (current == null) {
                sceneDiffs.add(new SceneDiff(baseScene.scene().id(), "removed", baseScene.index(), null,
                        List.of(), baseScene.scene(), null));
                continue;
            }
            List<String> changedFields = changedFields(baseScene.scene(), current.scene());
            if (!changedFields.isEmpty() || baseScene.index() != current.index()) {
                String changeType = changedFields.isEmpty() ? "moved" : "modified";
                sceneDiffs.add(new SceneDiff(baseScene.scene().id(), changeType, baseScene.index(), current.index(),
                        changedFields, baseScene.scene(), current.scene()));
            }
        }
        for (IndexedScene current : candidateScenes.values()) {
            if (!baseScenes.containsKey(current.scene().id())) {
                sceneDiffs.add(new SceneDiff(current.scene().id(), "added", null, current.index(), List.of(), null,
                        current.scene()));
            }
        }
        return new OutlineCandidateDiff(
                valueDiff(base.goal(), candidate.goal()),
                valueDiff(base.coreConflict(), candidate.coreConflict()),
                new CollectionDiff(!base.constraints().equals(candidate.constraints()), base.constraints(), candidate.constraints()),
                List.copyOf(sceneDiffs));
    }

    private Map<String, IndexedScene> indexed(List<Scene> scenes) {
        Map<String, IndexedScene> result = new LinkedHashMap<>();
        for (int index = 0; index < scenes.size(); index++) {
            Scene scene = scenes.get(index);
            result.put(scene.id(), new IndexedScene(index, scene));
        }
        return result;
    }

    private List<String> changedFields(Scene base, Scene candidate) {
        List<String> fields = new ArrayList<>();
        if (!base.title().equals(candidate.title())) {
            fields.add("title");
        }
        if (!base.content().equals(candidate.content())) {
            fields.add("content");
        }
        if (!base.tags().equals(candidate.tags())) {
            fields.add("tags");
        }
        return List.copyOf(fields);
    }

    private ValueDiff valueDiff(String beforeValue, String afterValue) {
        return new ValueDiff(!java.util.Objects.equals(beforeValue, afterValue), beforeValue, afterValue);
    }

    private record IndexedScene(int index, Scene scene) {
    }
}
