package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import java.util.Objects;

public record CraftingStep(
    SanitizedRecipe recipe,
    long timesApplied
) {
    public CraftingStep {
        Objects.requireNonNull(recipe, "recipe cannot be null");
        if (timesApplied <= 0) {
            throw new IllegalArgumentException("timesApplied must be larger than zero");
        }
    }
}
