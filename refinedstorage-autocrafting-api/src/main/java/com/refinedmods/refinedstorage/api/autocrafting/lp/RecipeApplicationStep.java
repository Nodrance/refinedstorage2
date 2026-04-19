package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.Objects;

// Represents the application of a single recipe a certain number of times, as part of a crafting path.
// This is the most granular step of crafting. A RecipeApplicationPath consists of a list of these.
public record RecipeApplicationStep(
    SanitizedRecipe recipe,
    long timesApplied
) {
    public RecipeApplicationStep {
        Objects.requireNonNull(recipe, "recipe cannot be null");
        if (timesApplied <= 0) {
            throw new IllegalArgumentException("timesApplied must be larger than zero");
        }
    }
}
