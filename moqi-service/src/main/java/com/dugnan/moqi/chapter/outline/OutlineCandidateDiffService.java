package com.dugnan.moqi.chapter.outline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.BeatDiff;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.CollectionDiff;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateDiff;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.ValueDiff;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Beat;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 基于稳定 beatKey 生成 V2 章纲候选与基础章纲的确定性差异。
 */
@Service
public class OutlineCandidateDiffService {
    public OutlineCandidateDiff diff(OutlineCandidateContent base, OutlineCandidateContent candidate) {
        Map<String, IndexedBeat> baseBeats = indexed(base.beats());
        Map<String, IndexedBeat> candidateBeats = indexed(candidate.beats());
        List<BeatDiff> differences = new ArrayList<>();
        for (IndexedBeat previous : baseBeats.values()) {
            IndexedBeat current = candidateBeats.get(previous.beat().beatKey());
            if (current == null) {
                differences.add(new BeatDiff(previous.beat().beatKey(), "removed", previous.index(), null,
                        List.of(), previous.beat(), null));
            } else if (!Objects.equals(previous.beat().summary(), current.beat().summary())
                    || previous.index() != current.index()) {
                List<String> fields = Objects.equals(previous.beat().summary(), current.beat().summary())
                        ? List.of() : List.of("summary");
                differences.add(new BeatDiff(previous.beat().beatKey(), fields.isEmpty() ? "moved" : "modified",
                        previous.index(), current.index(), fields, previous.beat(), current.beat()));
            }
        }
        for (IndexedBeat current : candidateBeats.values()) {
            if (!baseBeats.containsKey(current.beat().beatKey())) {
                differences.add(new BeatDiff(current.beat().beatKey(), "added", null, current.index(), List.of(),
                        null, current.beat()));
            }
        }
        return new OutlineCandidateDiff(valueDiff(base.goal(), candidate.goal()),
                valueDiff(base.coreConflict(), candidate.coreConflict()),
                new CollectionDiff(!base.constraints().equals(candidate.constraints()), base.constraints(), candidate.constraints()),
                List.copyOf(differences));
    }

    private Map<String, IndexedBeat> indexed(List<Beat> beats) {
        Map<String, IndexedBeat> result = new LinkedHashMap<>();
        for (int index = 0; index < beats.size(); index++) {
            result.put(beats.get(index).beatKey(), new IndexedBeat(index, beats.get(index)));
        }
        return result;
    }

    private ValueDiff valueDiff(String beforeValue, String afterValue) {
        return new ValueDiff(!Objects.equals(beforeValue, afterValue), beforeValue, afterValue);
    }

    private record IndexedBeat(int index, Beat beat) {
    }
}
