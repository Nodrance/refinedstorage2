package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CraftingProblem(
    List<SanitizedRecipe> sanitizedRecipes,
    ResourcePool startingResources,
    ResourcePool target,
    Set<MultiResourceKey> relevantResourceKeys,
    Map<ResourceKey, Long> sanitizedStartingResources,
    List<Pattern> relevantPatterns,
    List<Pattern> trimmedPatterns
) {
    public CraftingProblem {
        Objects.requireNonNull(sanitizedRecipes, "sanitizedRecipes cannot be null");
        Objects.requireNonNull(startingResources, "startingResources cannot be null");
        Objects.requireNonNull(target, "target cannot be null");
        Objects.requireNonNull(relevantResourceKeys, "relevantResourceKeys cannot be null");
        Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
        Objects.requireNonNull(relevantPatterns, "relevantPatterns cannot be null");
        Objects.requireNonNull(trimmedPatterns, "trimmedPatterns cannot be null");
        sanitizedRecipes = List.copyOf(sanitizedRecipes);
        startingResources = startingResources.copy();
        target = target.copy();
        relevantResourceKeys = Set.copyOf(relevantResourceKeys);
        sanitizedStartingResources = Map.copyOf(new LinkedHashMap<>(sanitizedStartingResources));
        relevantPatterns = List.copyOf(relevantPatterns);
        trimmedPatterns = List.copyOf(trimmedPatterns);
    }
}
