package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.Objects;

/**
 * Describes a single application of a recipe. This is used in the RecipeApplicationPath
 * to describe the steps taken to get from the initial inventory to the desired output.
 * Equivalent to old_lp's LpExecutionPlanStep.
 */
public record RecipeApplicationStep(
    ConcreteRecipe recipe,
    long timesApplied
) {
    public RecipeApplicationStep {
        Objects.requireNonNull(recipe, "recipe cannot be null");
        if (timesApplied <= 0) {
            throw new IllegalArgumentException("timesApplied must be larger than zero");
        }
    }
}
