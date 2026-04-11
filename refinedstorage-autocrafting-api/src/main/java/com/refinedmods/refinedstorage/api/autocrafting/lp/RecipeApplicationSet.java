package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Describes the result of an LP solution. Tells you how many times to apply recipes,
 * and tracks input resources, final inventory, and missing resources.
 * Equivalent to old_lp's LpCraftingSolution.
 */
public record RecipeApplicationSet(
    List<ConcreteRecipe> recipes,
    Map<ConcreteRecipe, Long> recipeValues,
    ResourcePool usedResources,
    ResourcePool finalInventoryValues,
    ResourcePool missingResources,
    List<Object> relevantResourceKeys
) {
    public RecipeApplicationSet {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(recipeValues, "recipeValues cannot be null");
        Objects.requireNonNull(usedResources, "usedResources cannot be null");
        Objects.requireNonNull(finalInventoryValues, "finalInventoryValues cannot be null");
        Objects.requireNonNull(missingResources, "missingResources cannot be null");
        Objects.requireNonNull(relevantResourceKeys, "relevantResourceKeys cannot be null");
        recipes = List.copyOf(recipes);
        recipeValues = Map.copyOf(new LinkedHashMap<>(recipeValues));
        relevantResourceKeys = List.copyOf(relevantResourceKeys);
    }
}
