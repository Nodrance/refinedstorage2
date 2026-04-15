package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.Objects;
import java.util.UUID;

/**
 * A recipe with no fuzziness. Represents a concrete LP recipe variant.
 * Equivalent to old_lp's LpPatternRecipe.
 */
public record ConcreteRecipe(
    UUID recipeId,
    UUID sourcePatternId,
    ResourcePool input,
    ResourcePool output,
    long priority,
    long insertionOrder
) {
    public ConcreteRecipe {
        Objects.requireNonNull(recipeId, "recipeId cannot be null");
        Objects.requireNonNull(sourcePatternId, "sourcePatternId cannot be null");
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(output, "output cannot be null");
    }
}
