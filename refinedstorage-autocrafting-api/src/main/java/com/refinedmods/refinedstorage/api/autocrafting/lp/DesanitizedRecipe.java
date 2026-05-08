package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// A fully desanitized recipe using only simple ResourceKey inputs and outputs.
// After desanitization, all MRKs have been converted to single ResourceKeys,
// and all ResourcePools have been converted to Map<ResourceKey, Long>.
public record DesanitizedRecipe(
    UUID recipeId,
    UUID sourcePatternId,
    Map<ResourceKey, Long> input,
    Map<ResourceKey, Long> output,
    long priority,
    long insertionOrder
) {
    public DesanitizedRecipe {
        Objects.requireNonNull(recipeId, "recipeId cannot be null");
        Objects.requireNonNull(sourcePatternId, "sourcePatternId cannot be null");
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(output, "output cannot be null");
        input = Map.copyOf(input);
        output = Map.copyOf(output);
    }
}
