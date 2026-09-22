package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Policy：第一版運行期間不可變的對照表。經 ConfigCodec 解碼後 nodes 與 required_targets 已排序，記錄相等即語意相等。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Policy(String deployment, List<String> nodes, List<String> namespaces, List<RequiredTargets> requiredTargets) {
    public Policy {
        nodes = List.copyOf(nodes);
        namespaces = List.copyOf(namespaces);
        requiredTargets = List.copyOf(requiredTargets);
    }

    public boolean isNamespaceRegistered(String namespace) {
        return namespaces.contains(namespace);
    }

    public boolean allowsWrite(String namespace, String sourceNode, String dataClass) {
        return isNamespaceRegistered(namespace) && targetsFor(sourceNode, dataClass).isPresent();
    }

    /** 未登錄的 (source, class) 回 empty；登錄但 targets 為空回 Optional.of(空集合)。 */
    public Optional<Set<String>> targetsFor(String sourceNode, String dataClass) {
        for (RequiredTargets rt : requiredTargets) {
            if (rt.sourceNode().equals(sourceNode) && rt.dataClass().equals(dataClass)) {
                return Optional.of(Set.copyOf(rt.targets()));
            }
        }
        return Optional.empty();
    }

    /** 排序後的等價 Policy，供比較與輸出。 */
    Policy normalized() {
        List<String> ns = new TreeSet<>(nodes).stream().toList();
        List<RequiredTargets> rts = requiredTargets.stream()
            .map(rt -> new RequiredTargets(rt.sourceNode(), rt.dataClass(), new TreeSet<>(rt.targets()).stream().toList()))
            .sorted(Comparator.comparing(RequiredTargets::sourceNode).thenComparing(RequiredTargets::dataClass))
            .toList();
        return new Policy(deployment, ns, new TreeSet<>(namespaces).stream().toList(), rts);
    }
}
