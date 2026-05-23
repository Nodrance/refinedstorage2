package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SanitizedRecipeResourceAnalyzer {
    private SanitizedRecipeResourceAnalyzer() {
    }

    public static List<MultiResourceKey> collectLeafResources(final List<SanitizedRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        final Set<MultiResourceKey> produced = new LinkedHashSet<>();
        final Set<MultiResourceKey> consumed = new LinkedHashSet<>();
        for (final SanitizedRecipe recipe : recipes) {
            produced.addAll(recipe.output().resourceKeys());
            consumed.addAll(recipe.input().resourceKeys());
        }

        final List<MultiResourceKey> leaves = new ArrayList<>();
        for (final MultiResourceKey resource : consumed) {
            if (!produced.contains(resource)) {
                leaves.add(resource);
            }
        }
        return leaves;
    }

    public static Set<MultiResourceKey> collectRelevantResourceKeys(final List<SanitizedRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        final Set<MultiResourceKey> relevant = new LinkedHashSet<>();
        for (final SanitizedRecipe recipe : recipes) {
            relevant.addAll(recipe.output().resourceKeys());
            relevant.addAll(recipe.input().resourceKeys());
        }
        return relevant;
    }
}