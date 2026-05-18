package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;

import java.util.Objects;
import java.util.UUID;

// A fully desanitized recipe using a PatternLayout with resolved single-resource ingredients.
// After desanitization, all MRKs have been converted to single ResourceKeys,
// and all ResourcePools have been converted to single-resource PatternLayout ingredients and outputs.
public record DesanitizedRecipe(
    UUID recipeId,
    UUID sourcePatternId,
    PatternLayout layout
) {
    public DesanitizedRecipe {
        Objects.requireNonNull(recipeId, "recipeId cannot be null");
        Objects.requireNonNull(sourcePatternId, "sourcePatternId cannot be null");
        Objects.requireNonNull(layout, "layout cannot be null");
    }
}
