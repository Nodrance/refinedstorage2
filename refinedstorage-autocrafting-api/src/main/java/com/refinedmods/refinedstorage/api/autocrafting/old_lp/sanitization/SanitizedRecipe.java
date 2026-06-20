package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import java.util.Objects;
import java.util.UUID;

// A recipe that uses only MultiResourceKeys as inputs and outputs
// This means there's no fuzziness, technically, as far as the crafting solver is concerned. 
public record SanitizedRecipe(
    UUID recipeId,
    UUID sourcePatternId,
    ResourcePool input,
    ResourcePool output,
    long priority,
    long insertionOrder
) {
    public SanitizedRecipe {
        Objects.requireNonNull(recipeId, "recipeId cannot be null");
        Objects.requireNonNull(sourcePatternId, "sourcePatternId cannot be null");
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(output, "output cannot be null");
    }
}
