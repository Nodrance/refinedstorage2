package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SanitizedRecipeCycleAnalyzer {
    private SanitizedRecipeCycleAnalyzer() {
    }

    public static SnippedRecipes snipLoopsOnTargetBranches(
        final List<SanitizedRecipe> recipes,
        final ResourcePool target
    ) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(target, "target cannot be null");

        final Set<UUID> snippedRecipeIds = collectLoopClosingRecipeIdsOnTargetBranches(recipes, target);
        final List<SanitizedRecipe> unsnipped = new ArrayList<>();
        final List<SanitizedRecipe> snipped = new ArrayList<>();
        for (final SanitizedRecipe recipe : recipes) {
            if (snippedRecipeIds.contains(recipe.recipeId())) {
                snipped.add(recipe);
            } else {
                unsnipped.add(recipe);
            }
        }
        return new SnippedRecipes(unsnipped, snipped);
    }

    public static CycleDetectionResult detectRecipeCycles(final List<SanitizedRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes cannot be null");

        final List<List<Integer>> adjacency = new ArrayList<>(recipes.size());
        for (int fromIndex = 0; fromIndex < recipes.size(); fromIndex++) {
            final SanitizedRecipe fromRecipe = recipes.get(fromIndex);
            final List<Integer> neighbors = new ArrayList<>();
            for (int toIndex = 0; toIndex < recipes.size(); toIndex++) {
                final SanitizedRecipe toRecipe = recipes.get(toIndex);
                if (recipeOutputsFeedRecipeInputs(fromRecipe, toRecipe)) {
                    neighbors.add(toIndex);
                }
            }
            adjacency.add(neighbors);
        }

        final Set<List<Integer>> seenCycles = new HashSet<>();
        final List<List<Integer>> cycleIndices = new ArrayList<>();
        for (int start = 0; start < recipes.size(); start++) {
            final boolean[] onPath = new boolean[recipes.size()];
            onPath[start] = true;
            final List<Integer> path = new ArrayList<>();
            path.add(start);
            depthFirstCollectCycles(start, start, adjacency, onPath, path, seenCycles, cycleIndices);
        }
        cycleIndices.sort(SanitizedRecipeCycleAnalyzer::compareIndexCycles);

        final Map<UUID, Boolean> inLoopByRecipeId = new LinkedHashMap<>();
        for (final SanitizedRecipe recipe : recipes) {
            inLoopByRecipeId.put(recipe.recipeId(), false);
        }

        final List<List<UUID>> cycles = new ArrayList<>();
        for (final List<Integer> cycle : cycleIndices) {
            final List<UUID> resolvedCycle = new ArrayList<>(cycle.size());
            for (final int index : cycle) {
                final SanitizedRecipe recipe = recipes.get(index);
                inLoopByRecipeId.put(recipe.recipeId(), true);
                resolvedCycle.add(recipe.recipeId());
            }
            cycles.add(List.copyOf(resolvedCycle));
        }

        return new CycleDetectionResult(inLoopByRecipeId, cycles);
    }

    public static Set<MultiResourceKey> collectLoopEntryDeficitResourcesOnTargetBranches(
        final List<SanitizedRecipe> recipes,
        final ResourcePool target
    ) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(target, "target cannot be null");

        final Map<MultiResourceKey, List<SanitizedRecipe>> outputToRecipes = buildOutputToRecipes(recipes);
        final Set<MultiResourceKey> loopEntryDeficitResources = new LinkedHashSet<>();
        for (final MultiResourceKey targetResource : target.resourceKeys()) {
            final Set<MultiResourceKey> pathResources = new LinkedHashSet<>();
            pathResources.add(targetResource);
            walkLoopEntryDeficits(targetResource, outputToRecipes, pathResources, loopEntryDeficitResources);
        }
        return loopEntryDeficitResources;
    }

    private static Set<UUID> collectLoopClosingRecipeIdsOnTargetBranches(
        final List<SanitizedRecipe> recipes,
        final ResourcePool target
    ) {
        final Map<MultiResourceKey, List<SanitizedRecipe>> outputToRecipes = buildOutputToRecipes(recipes);
        final Set<UUID> loopClosingRecipeIds = new LinkedHashSet<>();
        for (final MultiResourceKey targetResource : target.resourceKeys()) {
            final Set<MultiResourceKey> pathResources = new LinkedHashSet<>();
            pathResources.add(targetResource);
            walkLoopClosingRecipes(targetResource, outputToRecipes, pathResources, loopClosingRecipeIds);
        }
        return loopClosingRecipeIds;
    }

    private static Map<MultiResourceKey, List<SanitizedRecipe>> buildOutputToRecipes(
        final List<SanitizedRecipe> recipes
    ) {
        final Map<MultiResourceKey, List<SanitizedRecipe>> outputToRecipes = new HashMap<>();
        for (final SanitizedRecipe recipe : recipes) {
            for (final MultiResourceKey outputResource : recipe.output().resourceKeys()) {
                outputToRecipes.computeIfAbsent(outputResource, ignored -> new ArrayList<>()).add(recipe);
            }
        }
        return outputToRecipes;
    }

    private static void walkLoopClosingRecipes(
        final MultiResourceKey resource,
        final Map<MultiResourceKey, List<SanitizedRecipe>> outputToRecipes,
        final Set<MultiResourceKey> pathResources,
        final Set<UUID> loopClosingRecipeIds
    ) {
        final List<SanitizedRecipe> producingRecipes = outputToRecipes.get(resource);
        if (producingRecipes == null) {
            return;
        }
        for (final SanitizedRecipe recipe : producingRecipes) {
            for (final MultiResourceKey inputResource : recipe.input().resourceKeys()) {
                if (pathResources.contains(inputResource)) {
                    loopClosingRecipeIds.add(recipe.recipeId());
                    continue;
                }
                pathResources.add(inputResource);
                walkLoopClosingRecipes(inputResource, outputToRecipes, pathResources, loopClosingRecipeIds);
                pathResources.remove(inputResource);
            }
        }
    }

    private static void walkLoopEntryDeficits(
        final MultiResourceKey resource,
        final Map<MultiResourceKey, List<SanitizedRecipe>> outputToRecipes,
        final Set<MultiResourceKey> pathResources,
        final Set<MultiResourceKey> loopEntryDeficitResources
    ) {
        final List<SanitizedRecipe> producingRecipes = outputToRecipes.get(resource);
        if (producingRecipes == null) {
            return;
        }
        for (final SanitizedRecipe recipe : producingRecipes) {
            for (final MultiResourceKey inputResource : recipe.input().resourceKeys()) {
                if (pathResources.contains(inputResource)) {
                    loopEntryDeficitResources.add(resource);
                    continue;
                }
                pathResources.add(inputResource);
                walkLoopEntryDeficits(inputResource, outputToRecipes, pathResources, loopEntryDeficitResources);
                pathResources.remove(inputResource);
            }
        }
    }

    private static boolean recipeOutputsFeedRecipeInputs(
        final SanitizedRecipe fromRecipe,
        final SanitizedRecipe toRecipe
    ) {
        for (final MultiResourceKey outputResource : fromRecipe.output().resourceKeys()) {
            if (consumes(toRecipe, outputResource)) {
                return true;
            }
        }
        return false;
    }

    private static boolean consumes(final SanitizedRecipe recipe, final MultiResourceKey resource) {
        return recipe.input().getAmount(resource) > 0;
    }

    private static void depthFirstCollectCycles(
        final int start,
        final int current,
        final List<List<Integer>> adjacency,
        final boolean[] onPath,
        final List<Integer> path,
        final Set<List<Integer>> seenCycles,
        final List<List<Integer>> cycles
    ) {
        for (final int next : adjacency.get(current)) {
            if (next == start) {
                final List<Integer> canonical = canonicalizeCycle(path);
                if (seenCycles.add(canonical)) {
                    cycles.add(canonical);
                }
                continue;
            }
            if (onPath[next] || path.size() >= adjacency.size()) {
                continue;
            }
            onPath[next] = true;
            path.add(next);
            depthFirstCollectCycles(start, next, adjacency, onPath, path, seenCycles, cycles);
            path.remove(path.size() - 1);
            onPath[next] = false;
        }
    }

    private static List<Integer> canonicalizeCycle(final List<Integer> cycle) {
        if (cycle.isEmpty()) {
            return List.of();
        }
        List<Integer> best = List.copyOf(cycle);
        for (int shift = 1; shift < cycle.size(); shift++) {
            final List<Integer> rotated = new ArrayList<>(cycle.size());
            rotated.addAll(cycle.subList(shift, cycle.size()));
            rotated.addAll(cycle.subList(0, shift));
            if (compareIndexCycles(rotated, best) < 0) {
                best = List.copyOf(rotated);
            }
        }
        return best;
    }

    private static int compareIndexCycles(final List<Integer> left, final List<Integer> right) {
        final int sharedSize = Math.min(left.size(), right.size());
        for (int index = 0; index < sharedSize; index++) {
            final int compare = Integer.compare(left.get(index), right.get(index));
            if (compare != 0) {
                return compare;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    public record SnippedRecipes(List<SanitizedRecipe> unsnipped, List<SanitizedRecipe> snipped) {
        public SnippedRecipes {
            unsnipped = List.copyOf(unsnipped);
            snipped = List.copyOf(snipped);
        }
    }

    public record CycleDetectionResult(Map<UUID, Boolean> inLoopByRecipeId, List<List<UUID>> cycles) {
        public CycleDetectionResult {
            inLoopByRecipeId = Map.copyOf(inLoopByRecipeId);
            cycles = List.copyOf(cycles);
        }
    }
}