package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** (Source Node, Data class) → Required targets 的一列。targets 為空 = 只在本地、不同步。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RequiredTargets(String sourceNode, String dataClass, List<String> targets) {
    public RequiredTargets {
        targets = List.copyOf(targets);
    }
}
