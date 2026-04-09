package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import org.slf4j.Logger;

public final class LpStepPlanCalculator {
    private LpStepPlanCalculator() {
    }

    public static Optional<LpStepPlan> calculateSteps(final Collection<Pattern> patterns,
                                               final Logger logger,
                                               final RootStorage rootStorage,
                                               final ResourceKey resource,
                                               final long amount,
                                               final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            return Optional.empty();
        }

        final LpResourceSet startingResources = buildLpStartingResources(rootStorage);
        final LpFuzzyExpander.FuzzyExpansionResult expansionResult = buildFuzzyExpandedRecipes(
            patterns,
            startingResources
        );
        final List<LpPatternRecipe> recipes = expansionResult.expandedRecipes();
        if (recipes.isEmpty()) {
            return Optional.empty();
        }

        final LpResourceSet augmentedStarting = LpFuzzyExpander.augmentStartingResources(
            startingResources,
            expansionResult.createdSubsets()
        );
        final LpCraftingSolver.PlanningOutcome outcome;
        try {
            outcome = new LpCraftingSolver(cancellationToken).solve(
                recipes,
                augmentedStarting,
                buildTarget(rootStorage, resource, amount)
            );
        } catch (final CancellationException e) {
            return Optional.empty();
        }
        if (cancellationToken.isCancelled()) {
            return Optional.empty();
        }
        final Optional<List<LpExecutionPlanStep>> executableSteps = extractExecutableSteps(outcome);
        if (executableSteps.isEmpty()) {
            return Optional.empty();
        }

        List<LpExecutionPlanStep> steps = executableSteps.get();
        final boolean hasCycles = hasRecipeCycles(steps);
        steps = decodeFuzzyStepsIfNeeded(steps, expansionResult, patterns, startingResources);

        return steps.isEmpty() ? Optional.empty() : Optional.of(new LpStepPlan(steps, hasCycles));
    }

    private static Optional<List<LpExecutionPlanStep>> extractExecutableSteps(
        final LpCraftingSolver.PlanningOutcome outcome
    ) {
        return outcome.recipeApplicationResult()
            .filter(result -> result.requiredBaseItems().isEmpty())
            .map(LpCraftingSolver.RecipeApplicationPlanResult::plan);
    }

    private static List<LpExecutionPlanStep> decodeFuzzyStepsIfNeeded(
        final List<LpExecutionPlanStep> steps,
        final LpFuzzyExpander.FuzzyExpansionResult expansionResult,
        final Collection<Pattern> patterns,
        final LpResourceSet startingResources
    ) {
        if (expansionResult.variantToSourcePattern().isEmpty()) {
            return steps;
        }

        // Decode fuzzy variant steps back to original patterns with concrete inputs.
        final Map<UUID, Pattern> patternsById = new LinkedHashMap<>();
        for (final Pattern p : patterns) {
            patternsById.put(p.id(), p);
        }
        return LpFuzzyExpander.decodePlanSteps(
            steps,
            expansionResult.variantToSourcePattern(),
            patternsById,
            startingResources
        );
    }

    private static LpFuzzyExpander.FuzzyExpansionResult buildFuzzyExpandedRecipes(
        final Collection<Pattern> patterns,
        final LpResourceSet startingResources
    ) {
        final Set<ResourceKey> craftableResources = LpFuzzyExpander.collectCraftableResources(patterns);
        return LpFuzzyExpander.expandFuzzyPatterns(patterns, startingResources, craftableResources);
    }

    private static LpResourceSet buildLpStartingResources(final RootStorage rootStorage) {
        return LpResourceSet.fromResourceAmounts(rootStorage.getAll());
    }

    private static LpResourceSet buildTarget(final RootStorage rootStorage,
                                             final ResourceKey resource,
                                             final long amount) {
        final LpResourceSet target = new LpResourceSet();
        target.setAmount(resource, rootStorage.get(resource) + amount);
        return target;
    }

    public static boolean hasRecipeCycles(final List<LpExecutionPlanStep> steps) {
        final List<Pattern> patterns = steps.stream()
            .map(step -> step.recipe().pattern())
            .distinct()
            .toList();
        return hasPatternRecipeCycles(patterns);
    }

    public static long calculateMaxAmount(final Collection<Pattern> patterns,
                                          final Logger logger,
                                          final RootStorage rootStorage,
                                          final ResourceKey resource,
                                          final long amount) {
        return calculateMaxAmount(patterns, logger, rootStorage, resource, amount, CancellationToken.NONE);
    }

    public static long calculateMaxAmount(final Collection<Pattern> patterns,
                                          final Logger logger,
                                          final RootStorage rootStorage,
                                          final ResourceKey resource,
                                          final long amount,
                                          final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            return 0;
        }
        final LpResourceSet startingResources = buildLpStartingResources(rootStorage);
        final LpFuzzyExpander.FuzzyExpansionResult expansionResult = buildFuzzyExpandedRecipes(
            patterns, startingResources
        );
        final List<LpPatternRecipe> recipes = expansionResult.expandedRecipes();
        if (recipes.isEmpty()) {
            return 0;
        }
        final LpResourceSet augmentedStarting = LpFuzzyExpander.augmentStartingResources(
            startingResources, expansionResult.createdSubsets()
        );
        final LpCraftingSolver.PlanningOutcome outcome;
        try {
            outcome = new LpCraftingSolver(cancellationToken).solve(
                recipes,
                augmentedStarting,
                buildTarget(rootStorage, resource, 1)
            );
        } catch (final CancellationException e) {
            return 0;
        }
        if (cancellationToken.isCancelled()) {
            return 0;
        }
        return Math.min(outcome.maxCraftableAmount(), amount);
    }

    public static boolean hasPatternRecipeCycles(final Collection<Pattern> patterns) {
        final List<Pattern> patternList = patterns.stream().distinct().toList();
        if (patternList.isEmpty()) {
            return false;
        }

        final Map<Pattern, Set<ResourceKey>> producedByPattern = new HashMap<>();
        final Map<Pattern, Set<ResourceKey>> consumedByPattern = new HashMap<>();

        for (final Pattern pattern : patternList) {
            final Set<ResourceKey> produced = new HashSet<>();
            pattern.layout().outputs().forEach(output -> produced.add(output.resource()));
            pattern.layout().byproducts().forEach(byproduct -> produced.add(byproduct.resource()));
            producedByPattern.put(pattern, produced);

            final Set<ResourceKey> consumed = new HashSet<>();
            pattern.layout().ingredients().forEach(ingredient -> consumed.add(ingredient.inputs().getFirst()));
            consumedByPattern.put(pattern, consumed);
        }

        final Map<Pattern, Set<Pattern>> dependencies = new HashMap<>();
        for (final Pattern pattern : patternList) {
            dependencies.putIfAbsent(pattern, new HashSet<>());
            final Set<ResourceKey> produced = producedByPattern.get(pattern);
            for (final Pattern target : patternList) {
                final Set<ResourceKey> consumed = consumedByPattern.get(target);
                if (produced.stream().anyMatch(consumed::contains)) {
                    dependencies.get(pattern).add(target);
                }
            }
        }

        final Set<Pattern> visiting = new HashSet<>();
        final Set<Pattern> visited = new HashSet<>();
        for (final Pattern pattern : patternList) {
            if (containsCycle(pattern, dependencies, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCycle(final Pattern current,
                                         final Map<Pattern, Set<Pattern>> dependencies,
                                         final Set<Pattern> visiting,
                                         final Set<Pattern> visited) {
        if (visited.contains(current)) {
            return false;
        }
        if (!visiting.add(current)) {
            return true;
        }
        for (final Pattern dependency : dependencies.getOrDefault(current, Collections.emptySet())) {
            if (containsCycle(dependency, dependencies, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(current);
        visited.add(current);
        return false;
    }
}
