package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class RecipeAnalyzer {
    private RecipeAnalyzer() {
    }

    public static List<MultiResourceKey> collectLeafResources(final List<ConcreteRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        final Set<MultiResourceKey> produced = new LinkedHashSet<>();
        final Set<MultiResourceKey> consumed = new LinkedHashSet<>();
        for (final ConcreteRecipe recipe : recipes) {
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

    public static SnippedRecipes snipLoopsOnTargetBranches(
        final List<ConcreteRecipe> recipes,
        final ResourcePool target
    ) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(target, "target cannot be null");

        final Set<UUID> snippedRecipeIds = collectLoopClosingRecipeIdsOnTargetBranches(recipes, target);
        final List<ConcreteRecipe> unsnipped = new ArrayList<>();
        final List<ConcreteRecipe> snipped = new ArrayList<>();
        for (final ConcreteRecipe recipe : recipes) {
            if (snippedRecipeIds.contains(recipe.recipeId())) {
                snipped.add(recipe);
            } else {
                unsnipped.add(recipe);
            }
        }
        return new SnippedRecipes(unsnipped, snipped);
    }

    public static CycleDetectionResult detectRecipeCycles(final List<ConcreteRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes cannot be null");

        final List<List<Integer>> adjacency = new ArrayList<>(recipes.size());
        for (int fromIndex = 0; fromIndex < recipes.size(); fromIndex++) {
            final ConcreteRecipe fromRecipe = recipes.get(fromIndex);
            final List<Integer> neighbors = new ArrayList<>();
            for (int toIndex = 0; toIndex < recipes.size(); toIndex++) {
                final ConcreteRecipe toRecipe = recipes.get(toIndex);
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
        cycleIndices.sort(RecipeAnalyzer::compareIndexCycles);

        final Map<UUID, Boolean> inLoopByRecipeId = new LinkedHashMap<>();
        for (final ConcreteRecipe recipe : recipes) {
            inLoopByRecipeId.put(recipe.recipeId(), false);
        }

        final List<List<UUID>> cycles = new ArrayList<>();
        for (final List<Integer> cycle : cycleIndices) {
            final List<UUID> resolvedCycle = new ArrayList<>(cycle.size());
            for (final int index : cycle) {
                final ConcreteRecipe recipe = recipes.get(index);
                inLoopByRecipeId.put(recipe.recipeId(), true);
                resolvedCycle.add(recipe.recipeId());
            }
            cycles.add(List.copyOf(resolvedCycle));
        }

        return new CycleDetectionResult(inLoopByRecipeId, cycles);
    }

    public static Set<MultiResourceKey> collectRelevantResourceKeys(final List<ConcreteRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        final Set<MultiResourceKey> relevant = new LinkedHashSet<>();
        for (final ConcreteRecipe recipe : recipes) {
            relevant.addAll(recipe.output().resourceKeys());
            relevant.addAll(recipe.input().resourceKeys());
        }
        return relevant;
    }

    public static List<ConcreteRecipe> applyEffectivePriorities(
        final List<ConcreteRecipe> recipes,
        final ResourcePool target
    ) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(target, "target cannot be null");

        final TraversalState state = initializeTraversalState(target);
        propagatePriorities(recipes, state);
        return buildPrioritizedRecipes(recipes, state.bestRecipePriorities());
    }

    public static List<ConcreteRecipe> selectTopPriorityRecipesPerOutputResource(final List<ConcreteRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes cannot be null");

        final List<ConcreteRecipe> sorted = recipes.stream()
            .sorted(Comparator
                .comparingLong(ConcreteRecipe::priority)
                .reversed()
                .thenComparingLong(ConcreteRecipe::insertionOrder)
                .thenComparing(ConcreteRecipe::recipeId))
            .toList();

        final Set<MultiResourceKey> seenOutputResources = new LinkedHashSet<>();
        final Set<UUID> selectedRecipeIds = new LinkedHashSet<>();
        for (final ConcreteRecipe recipe : sorted) {
            boolean producesNewResource = false;
            for (final MultiResourceKey resource : recipe.output().resourceKeys()) {
                if (!seenOutputResources.contains(resource)) {
                    producesNewResource = true;
                    break;
                }
            }
            if (!producesNewResource) {
                continue;
            }
            selectedRecipeIds.add(recipe.recipeId());
            seenOutputResources.addAll(recipe.output().resourceKeys());
        }

        final List<ConcreteRecipe> selected = new ArrayList<>();
        for (final ConcreteRecipe recipe : sorted) {
            if (selectedRecipeIds.contains(recipe.recipeId())) {
                selected.add(recipe);
            }
        }
        return selected;
    }

    public static Set<MultiResourceKey> collectLoopEntryDeficitResourcesOnTargetBranches(
        final List<ConcreteRecipe> recipes,
        final ResourcePool target
    ) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(target, "target cannot be null");

        final Map<MultiResourceKey, List<ConcreteRecipe>> outputToRecipes = buildOutputToRecipes(recipes);
        final Set<MultiResourceKey> loopEntryDeficitResources = new LinkedHashSet<>();
        for (final MultiResourceKey targetResource : target.resourceKeys()) {
            final Set<MultiResourceKey> pathResources = new LinkedHashSet<>();
            pathResources.add(targetResource);
            walkLoopEntryDeficits(targetResource, outputToRecipes, pathResources, loopEntryDeficitResources);
        }
        return loopEntryDeficitResources;
    }

    private static TraversalState initializeTraversalState(final ResourcePool target) {
        final Map<MultiResourceKey, PriorityKey> bestResourcePriorities = new LinkedHashMap<>();
        final Map<UUID, PriorityKey> bestRecipePriorities = new LinkedHashMap<>();
        final Deque<ResourcePriorityEntry> queue = new ArrayDeque<>();
        final PriorityKey basePriority = new PriorityKey();
        for (final MultiResourceKey targetResource : target.resourceKeys()) {
            bestResourcePriorities.put(targetResource, basePriority);
            queue.addLast(new ResourcePriorityEntry(targetResource, basePriority));
        }
        return new TraversalState(bestResourcePriorities, bestRecipePriorities, queue);
    }

    private static void propagatePriorities(final List<ConcreteRecipe> recipes, final TraversalState state) {
        while (!state.queue().isEmpty()) {
            final ResourcePriorityEntry outputEntry = state.queue().removeLast();
            applyOutputPriorityToRecipes(recipes, outputEntry, state);
        }
    }

    private static void applyOutputPriorityToRecipes(
        final List<ConcreteRecipe> recipes,
        final ResourcePriorityEntry outputEntry,
        final TraversalState state
    ) {
        for (final ConcreteRecipe recipe : recipes) {
            if (!produces(recipe, outputEntry.resource())) {
                continue;
            }

            final PriorityKey candidateRecipePriority = outputEntry.priority().appendRecipePriority(recipe);
            if (!tryUpdateBestRecipePriority(
                recipe.recipeId(),
                candidateRecipePriority,
                state.bestRecipePriorities()
            )) {
                continue;
            }
            pushImprovedInputPriorities(recipe, candidateRecipePriority, state);
        }
    }

    private static boolean tryUpdateBestRecipePriority(
        final UUID recipeId,
        final PriorityKey candidatePriority,
        final Map<UUID, PriorityKey> bestRecipePriorities
    ) {
        final PriorityKey currentPriority = bestRecipePriorities.get(recipeId);
        if (currentPriority != null && candidatePriority.compareTo(currentPriority) >= 0) {
            return false;
        }
        bestRecipePriorities.put(recipeId, candidatePriority);
        return true;
    }

    private static void pushImprovedInputPriorities(
        final ConcreteRecipe recipe,
        final PriorityKey candidateRecipePriority,
        final TraversalState state
    ) {
        for (final MultiResourceKey inputResource : recipe.input().resourceKeys()) {
            final PriorityKey currentInputPriority = state.bestResourcePriorities().get(inputResource);
            if (currentInputPriority != null && candidateRecipePriority.compareTo(currentInputPriority) >= 0) {
                continue;
            }
            state.bestResourcePriorities().put(inputResource, candidateRecipePriority);
            state.queue().addLast(new ResourcePriorityEntry(inputResource, candidateRecipePriority));
        }
    }

    private static List<ConcreteRecipe> buildPrioritizedRecipes(
        final List<ConcreteRecipe> recipes,
        final Map<UUID, PriorityKey> bestRecipePriorities
    ) {
        final List<RecipePriorityEntry> prunedRecipesWithPriority = collectPrunedRecipesWithPriority(
            recipes,
            bestRecipePriorities
        );
        final List<ConcreteRecipe> updatedRecipes = assignEffectivePriorities(prunedRecipesWithPriority, recipes);
        return mapEntriesToRecipes(prunedRecipesWithPriority, updatedRecipes);
    }

    private static List<RecipePriorityEntry> collectPrunedRecipesWithPriority(
        final List<ConcreteRecipe> recipes,
        final Map<UUID, PriorityKey> bestRecipePriorities
    ) {
        final Map<UUID, ConcreteRecipe> recipeById = new HashMap<>();
        for (final ConcreteRecipe recipe : recipes) {
            recipeById.put(recipe.recipeId(), recipe);
        }

        final List<RecipePriorityEntry> entries = new ArrayList<>();
        for (final ConcreteRecipe recipe : recipes) {
            final PriorityKey priority = bestRecipePriorities.get(recipe.recipeId());
            if (priority != null) {
                entries.add(new RecipePriorityEntry(recipe.recipeId(), priority));
            }
        }
        entries.sort(
            Comparator.comparing(RecipePriorityEntry::priority)
                .thenComparingLong(entry -> recipeById.get(entry.recipeId()).insertionOrder())
                .thenComparing(RecipePriorityEntry::recipeId)
        );
        return entries;
    }

    private static List<ConcreteRecipe> assignEffectivePriorities(
        final List<RecipePriorityEntry> sortedEntries,
        final List<ConcreteRecipe> recipes
    ) {
        final Map<UUID, Long> effectivePriorities = new LinkedHashMap<>();
        for (int index = 0; index < sortedEntries.size(); index++) {
            final RecipePriorityEntry recipeAndPriority = sortedEntries.get(index);
            effectivePriorities.put(recipeAndPriority.recipeId(), (long) (sortedEntries.size() - 1 - index));
        }

        final List<ConcreteRecipe> result = new ArrayList<>(recipes.size());
        for (final ConcreteRecipe recipe : recipes) {
            final Long effectivePriority = effectivePriorities.get(recipe.recipeId());
            if (effectivePriority != null) {
                result.add(new ConcreteRecipe(
                    recipe.recipeId(),
                    recipe.sourcePatternId(),
                    recipe.input(),
                    recipe.output(),
                    effectivePriority,
                    recipe.insertionOrder()
                ));
            } else {
                result.add(recipe);
            }
        }
        return result;
    }

    private static List<ConcreteRecipe> mapEntriesToRecipes(
        final List<RecipePriorityEntry> sortedEntries,
        final List<ConcreteRecipe> recipes
    ) {
        final Map<UUID, ConcreteRecipe> byId = new LinkedHashMap<>();
        for (final ConcreteRecipe recipe : recipes) {
            byId.put(recipe.recipeId(), recipe);
        }
        final List<ConcreteRecipe> prioritizedRecipes = new ArrayList<>(sortedEntries.size());
        for (final RecipePriorityEntry entry : sortedEntries) {
            prioritizedRecipes.add(byId.get(entry.recipeId()));
        }
        return prioritizedRecipes;
    }

    private static Set<UUID> collectLoopClosingRecipeIdsOnTargetBranches(
        final List<ConcreteRecipe> recipes,
        final ResourcePool target
    ) {
        final Map<MultiResourceKey, List<ConcreteRecipe>> outputToRecipes = buildOutputToRecipes(recipes);
        final Set<UUID> loopClosingRecipeIds = new LinkedHashSet<>();
        for (final MultiResourceKey targetResource : target.resourceKeys()) {
            final Set<MultiResourceKey> pathResources = new LinkedHashSet<>();
            pathResources.add(targetResource);
            walkLoopClosingRecipes(targetResource, outputToRecipes, pathResources, loopClosingRecipeIds);
        }
        return loopClosingRecipeIds;
    }

    private static Map<MultiResourceKey, List<ConcreteRecipe>> buildOutputToRecipes(
        final List<ConcreteRecipe> recipes
    ) {
        final Map<MultiResourceKey, List<ConcreteRecipe>> outputToRecipes = new HashMap<>();
        for (final ConcreteRecipe recipe : recipes) {
            for (final MultiResourceKey outputResource : recipe.output().resourceKeys()) {
                outputToRecipes.computeIfAbsent(outputResource, ignored -> new ArrayList<>()).add(recipe);
            }
        }
        return outputToRecipes;
    }

    private static void walkLoopClosingRecipes(
        final MultiResourceKey resource,
        final Map<MultiResourceKey, List<ConcreteRecipe>> outputToRecipes,
        final Set<MultiResourceKey> pathResources,
        final Set<UUID> loopClosingRecipeIds
    ) {
        final List<ConcreteRecipe> producingRecipes = outputToRecipes.get(resource);
        if (producingRecipes == null) {
            return;
        }
        for (final ConcreteRecipe recipe : producingRecipes) {
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
        final Map<MultiResourceKey, List<ConcreteRecipe>> outputToRecipes,
        final Set<MultiResourceKey> pathResources,
        final Set<MultiResourceKey> loopEntryDeficitResources
    ) {
        final List<ConcreteRecipe> producingRecipes = outputToRecipes.get(resource);
        if (producingRecipes == null) {
            return;
        }
        for (final ConcreteRecipe recipe : producingRecipes) {
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
        final ConcreteRecipe fromRecipe,
        final ConcreteRecipe toRecipe
    ) {
        for (final MultiResourceKey outputResource : fromRecipe.output().resourceKeys()) {
            if (consumes(toRecipe, outputResource)) {
                return true;
            }
        }
        return false;
    }

    private static boolean produces(final ConcreteRecipe recipe, final MultiResourceKey resource) {
        return recipe.output().getAmount(resource) > 0;
    }

    private static boolean consumes(final ConcreteRecipe recipe, final MultiResourceKey resource) {
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
        for (int i = 0; i < sharedSize; i++) {
            final int compare = Integer.compare(left.get(i), right.get(i));
            if (compare != 0) {
                return compare;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    public record SnippedRecipes(List<ConcreteRecipe> unsnipped, List<ConcreteRecipe> snipped) {
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

    private record ResourcePriorityEntry(MultiResourceKey resource, PriorityKey priority) {
    }

    private record TraversalState(
        Map<MultiResourceKey, PriorityKey> bestResourcePriorities,
        Map<UUID, PriorityKey> bestRecipePriorities,
        Deque<ResourcePriorityEntry> queue
    ) {
    }

    private record RecipePriorityEntry(UUID recipeId, PriorityKey priority) {
    }

    private static final class PriorityKey implements Comparable<PriorityKey> {
        private final List<Long> values;

        private PriorityKey() {
            this.values = List.of();
        }

        private PriorityKey(final List<Long> values) {
            this.values = List.copyOf(values);
        }

        private PriorityKey appendRecipePriority(final ConcreteRecipe recipe) {
            final List<Long> copy = new ArrayList<>(values);
            copy.add(recipe.priority());
            return new PriorityKey(copy);
        }

        @Override
        public int compareTo(final PriorityKey other) {
            final int sharedSize = Math.min(values.size(), other.values.size());
            for (int i = 0; i < sharedSize; i++) {
                final long left = values.get(i);
                final long right = other.values.get(i);
                if (left != right) {
                    return Long.compare(right, left);
                }
            }
            return Integer.compare(values.size(), other.values.size());
        }
    }
}
