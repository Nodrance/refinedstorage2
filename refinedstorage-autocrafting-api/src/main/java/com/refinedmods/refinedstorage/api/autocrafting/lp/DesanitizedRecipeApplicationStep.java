package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.Objects;

// Represents the application of a single desanitized recipe a certain number of times,
// as part of a desanitized crafting path. This is the most granular step of crafting.
// A DesanitizedRecipeApplicationPath consists of a list of these.
public record DesanitizedRecipeApplicationStep(
    DesanitizedRecipe recipe,
    long timesApplied
) {
    public DesanitizedRecipeApplicationStep {
        Objects.requireNonNull(recipe, "recipe cannot be null");
        if (timesApplied <= 0) {
            throw new IllegalArgumentException("timesApplied must be larger than zero");
        }
    }
}
