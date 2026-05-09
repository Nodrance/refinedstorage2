package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CraftingSolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingSolver.class);

    private final LinearSolver.Options options;
    private final CancellationToken cancellationToken;
    private final CancellationToken loopSnippingCancellationToken;

    public CraftingSolver() {
        this(LinearSolver.Options.defaults(), CancellationToken.NONE, CancellationToken.NONE);
    }

    public CraftingSolver(final CancellationToken cancellationToken) {
        this(LinearSolver.Options.defaults(), cancellationToken, cancellationToken);
    }

    public CraftingSolver(
        final CancellationToken cancellationToken,
        final CancellationToken loopSnippingCancellationToken
    ) {
        this(LinearSolver.Options.defaults(), cancellationToken, loopSnippingCancellationToken);
    }

    public CraftingSolver(final LinearSolver.Options options) {
        this(options, CancellationToken.NONE, CancellationToken.NONE);
    }

    public CraftingSolver(final LinearSolver.Options options, final CancellationToken cancellationToken) {
        this(options, cancellationToken, cancellationToken);
    }

    public CraftingSolver(
        final LinearSolver.Options options,
        final CancellationToken cancellationToken,
        final CancellationToken loopSnippingCancellationToken
    ) {
        this.options = Objects.requireNonNull(options, "options cannot be null");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        this.loopSnippingCancellationToken = Objects.requireNonNull(
            loopSnippingCancellationToken,
            "loopSnippingCancellationToken cannot be null"
        );
    }

    public Optional<RecipeApplicationPath> solve(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final ResourcePool target
    ) {
        validateInputs(recipes, startingResources, target);
        try {
            throwIfCancelled();
            final boolean canCraftTarget = canCraftTarget(recipes, startingResources, target);
            LOGGER.info(
                "[LP] solve: initial feasibility check result for target {} is {}",
                target,
                canCraftTarget
            );
            throwIfCancelled();

            if (!canCraftTarget) {
                final DeficitAnalysisResult deficitAnalysis =
                    computeRequiredBaseItemsAndSolution(recipes, startingResources, target);
                LOGGER.info(
                    "[LP] solve: target requires base items (resourceCount={}, totalAmount={})",
                    deficitAnalysis.requiredBaseItems(),
                    totalAmount(deficitAnalysis.requiredBaseItems())
                );
                throwIfCancelled();
                return buildRecipeApplicationPath(recipes, startingResources, deficitAnalysis);
            }

            final CycleEliminationResult cycleEliminationResult;
            try {
                cycleEliminationResult = findRecipeApplicationPlanViaCycleEliminationInternal(
                    recipes,
                    startingResources,
                    target
                );
            } catch (final CancellationException e) {
                if (cancellationToken.isCancelled()) {
                    throw e;
                }
                LOGGER.info(
                    "[LP] solve: loop snipping cancelled, continuing with fallback deficit analysis"
                );
                return buildRecipeApplicationPath(
                    recipes,
                    startingResources,
                    computeRequiredBaseItemsAndSolution(recipes, startingResources, target)
                );
            }
            throwIfCancelled();
            if (cycleEliminationResult.recipeApplicationResult().isPresent()) {
                LOGGER.info("[LP] solve: found executable recipe application path without fallback deficit analysis");
                return cycleEliminationResult.recipeApplicationResult();
            }

            final Set<UUID> disabledRecipeIds = cycleEliminationResult.fallbackDisabledRecipeIds();
            final List<SanitizedRecipe> reducedRecipes = recipes.stream()
                .filter(recipe -> !disabledRecipeIds.contains(recipe.recipeId()))
                .toList();
            LOGGER.info(
                "[LP] solve: cycle elimination fallback disabled {} recipes; retrying with {} recipes",
                disabledRecipeIds.size(),
                reducedRecipes.size()
            );
            final DeficitAnalysisResult deficitAnalysis =
                computeRequiredBaseItemsAndSolution(reducedRecipes, startingResources, target);
            LOGGER.info(
                "[LP] solve: fallback deficit analysis requires base items {}",
                deficitAnalysis.requiredBaseItems()
            );
            throwIfCancelled();
            return buildRecipeApplicationPath(reducedRecipes, startingResources, deficitAnalysis);
        } catch (final CancellationException e) {
            LOGGER.info("[LP] solve() cancelled.");
            return Optional.empty();
        }
    }

    public boolean canCraftWithoutMissingResources(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final ResourcePool target
    ) {
        validateInputs(recipes, startingResources, target);
        try {
            throwIfCancelled();

            if (!canCraftTarget(recipes, startingResources, target)) {
                // Fast path: this would immediately enter deficit analysis in solve(...).
                return false;
            }

            final CycleEliminationResult cycleEliminationResult =
                findRecipeApplicationPlanViaCycleEliminationInternal(recipes, startingResources, target);

            // Fast path: if cycle elimination cannot build an executable path,
            // solve(...) would enter fallback deficit analysis.
            return cycleEliminationResult.recipeApplicationResult().isPresent();
        } catch (final CancellationException e) {
            LOGGER.info("[LP] canCraftWithoutMissingResources() cancelled.");
            return false;
        }
    }

    public ResourcePool computeRequiredBaseItems(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final ResourcePool target
    ) {
        validateInputs(recipes, startingResources, target);
        try {
            throwIfCancelled();
            final ResourcePool required = computeRequiredBaseItemsAndSolution(recipes, startingResources, target)
                .requiredBaseItems();
            LOGGER.info(
                "[LP] computeRequiredBaseItems: target={} required={}",
                target,
                required
            );
            return required;
        } catch (final CancellationException e) {
            LOGGER.info("[LP] computeRequiredBaseItems() cancelled.");
            return ResourcePool.empty();
        }
    }

    private CycleEliminationResult findRecipeApplicationPlanViaCycleElimination(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final ResourcePool target
    ) {
        validateInputs(recipes, startingResources, target);
        try {
            throwIfCancelled();
            return findRecipeApplicationPlanViaCycleEliminationInternal(recipes, startingResources, target);
        } catch (final CancellationException e) {
            LOGGER.info("[LP] findRecipeApplicationPlanViaCycleElimination() cancelled.");
            return new CycleEliminationResult(Optional.empty(), Set.of());
        }
    }

    private boolean canCraftTarget(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final ResourcePool target
    ) {
        validateInputs(recipes, startingResources, target);
        if (target.isEmpty()) {
            return true;
        }

        final Set<MultiResourceKey> relevantResources = new LinkedHashSet<>(
            RecipeAnalyzer.collectRelevantResourceKeys(recipes)
        );
        relevantResources.addAll(target.resourceKeys());

        return new LinearSolver(
            recipes,
            relevantResources,
            startingResources,
            target,
            Set.of(),
            Set.of(),
            options,
            cancellationToken
        ).hasFeasibleSolution();
    }

    private DeficitAnalysisResult computeRequiredBaseItemsAndSolution(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final ResourcePool target
    ) {
        validateInputs(recipes, startingResources, target);
        throwIfCancelled();
        LOGGER.info("[LP] computeRequiredBaseItemsAndSolution: analyzing deficit for target {}", target);

        // COMPATABILITY
        // Comment this line if you want it to actually care about recipe priority
        // instead of trying to match the traditional solver
        final List<SanitizedRecipe> deficitPriorityRecipes = reverseRecipePrioritiesForDeficitAnalysis(recipes);

        final List<SanitizedRecipe> selectedRecipes =
            RecipeAnalyzer.selectTopPriorityRecipesPerOutputResource(deficitPriorityRecipes);

        final Set<MultiResourceKey> leafDeficitResources = new LinkedHashSet<>(
            RecipeAnalyzer.collectLeafResources(selectedRecipes)
        );
        if (!leafDeficitResources.isEmpty()) {
            LOGGER.info(
                "[LP] computeRequiredBaseItemsAndSolution: pass=leaf selectedRecipes={} totalRecipes={} leafDeficitResources={}",
                selectedRecipes.size(),
                recipes.size(),
                leafDeficitResources
            );
            return analyzeDeficitResources(
                deficitPriorityRecipes,
                selectedRecipes,
                leafDeficitResources,
                startingResources,
                target,
                "leaf"
            );
        }

        final RecipeAnalyzer.SnippedRecipes snippedRecipes = RecipeAnalyzer.snipLoopsOnTargetBranches(
            selectedRecipes,
            target
        );
        final List<SanitizedRecipe> deficitRecipes = snippedRecipes.unsnipped();
        LOGGER.info(
            "[LP] computeRequiredBaseItemsAndSolution: selectedRecipes={} snippedRecipes={} totalRecipes={}",
            selectedRecipes.size(),
            snippedRecipes.snipped().size(),
            recipes.size()
        );
        final Set<MultiResourceKey> deficitResources = new LinkedHashSet<>(
            RecipeAnalyzer.collectLeafResources(deficitRecipes)
        );
        deficitResources.addAll(
            RecipeAnalyzer.collectLoopEntryDeficitResourcesOnTargetBranches(selectedRecipes, target)
        );
        return analyzeDeficitResources(
            deficitPriorityRecipes,
            deficitRecipes,
            deficitResources,
            startingResources,
            target,
            "snipped"
        );
    }

    private DeficitAnalysisResult analyzeDeficitResources(
        final List<SanitizedRecipe> optimizationRecipes,
        final List<SanitizedRecipe> deficitRecipes,
        final Set<MultiResourceKey> initialDeficitResources,
        final ResourcePool startingResources,
        final ResourcePool target,
        final String passName
    ) {
        final Set<MultiResourceKey> relevantResources = RecipeAnalyzer.collectRelevantResourceKeys(deficitRecipes);
        relevantResources.addAll(target.resourceKeys());

        final Set<MultiResourceKey> deficitResources = new LinkedHashSet<>(initialDeficitResources);
        if (deficitResources.isEmpty()) {
            deficitResources.addAll(target.resourceKeys());
        }
        LOGGER.info("[LP] computeRequiredBaseItemsAndSolution({}): deficit resources={}", passName, deficitResources);

        if (deficitRecipes.isEmpty()) {
            final ResourcePool required = new ResourcePool();
            for (final MultiResourceKey resource : deficitResources) {
                throwIfCancelled();
                final long needed = Math.max(0L, target.getAmount(resource) - startingResources.getAmount(resource));
                if (needed > 0) {
                    required.addAmount(resource, needed);
                }
            }
            return new DeficitAnalysisResult(required, Optional.empty());
        }

        final Set<MultiResourceKey> unconstrainedResources = new LinkedHashSet<>(deficitResources);

        final LinearSolver.Result result = new LinearSolver(
            deficitRecipes,
            relevantResources,
            startingResources,
            target,
            unconstrainedResources,
            Set.of(),
            options,
            cancellationToken
        ).lexicographicMinimum();

        LinearSolver.Result selectedResult = result;
        ResourcePool required = computeRequiredBaseItems(deficitResources, startingResources, target, selectedResult);
        LOGGER.info(
            "[LP] computeRequiredBaseItemsAndSolution({}): initialDeficitResources={} initialTotalDeficit={}",
            passName,
            required,
            totalDeficit(required)
        );

        if (selectedResult != null && !required.isEmpty()) {
            final LinearSolver.Result currentResult = selectedResult;
            final List<SanitizedRecipe> usedRecipes = deficitRecipes.stream()
                .filter(recipe -> currentResult.recipeValues().getOrDefault(recipe.recipeId(), 0L) > 0L)
                .toList();
            final RecipeAnalyzer.CycleDetectionResult cycleDetectionResult =
                RecipeAnalyzer.detectRecipeCycles(usedRecipes);
            if (cycleDetectionResult.cycles().isEmpty()) {
                final Set<MultiResourceKey> optimizationRelevantResources = new LinkedHashSet<>(
                    RecipeAnalyzer.collectRelevantResourceKeys(optimizationRecipes)
                );
                optimizationRelevantResources.addAll(target.resourceKeys());
                LOGGER.info(
                    "[LP] computeRequiredBaseItemsAndSolution({}): attempting deficit optimization "
                        + "with full recipe set (selectedRecipes={}, fullRecipes={})",
                    passName,
                    deficitRecipes.size(),
                    optimizationRecipes.size()
                );
                final Optional<LinearSolver.Result> optimizedResult = optimizeDeficitResources(
                    optimizationRecipes,
                    optimizationRelevantResources,
                    startingResources,
                    target,
                    unconstrainedResources,
                    required,
                    currentResult
                );
                if (optimizedResult.isPresent()) {
                    selectedResult = optimizedResult.get();
                    required = computeRequiredBaseItems(deficitResources, startingResources, target, selectedResult);
                    LOGGER.info(
                        "[LP] computeRequiredBaseItemsAndSolution({}): optimizedDeficitResources={} "
                            + "optimizedTotalDeficit={}",
                        passName,
                        required,
                        totalDeficit(required)
                    );
                }
            } else {
                LOGGER.info(
                    "[LP] computeRequiredBaseItemsAndSolution({}): skipped deficit optimization "
                        + "because cycles were detected",
                    passName
                );
            }
        }

        return new DeficitAnalysisResult(required, Optional.ofNullable(selectedResult));
    }

    private Optional<LinearSolver.Result> optimizeDeficitResources(
        final List<SanitizedRecipe> optimizationRecipes,
        final Set<MultiResourceKey> relevantResources,
        final ResourcePool startingResources,
        final ResourcePool target,
        final Set<MultiResourceKey> unconstrainedResources,
        final ResourcePool requiredBaseItems,
        final LinearSolver.Result currentSolution
    ) {
        throwIfCancelled();
        final Set<MultiResourceKey> deficitResources = new LinkedHashSet<>(requiredBaseItems.resourceKeys());
        final long before = totalDeficit(requiredBaseItems);
        if (deficitResources.isEmpty()) {
            LOGGER.info(
                "[LP] optimizeDeficitResources: totalDeficitBefore={} totalDeficitAfter={} deficitDecreased={}",
                before,
                before,
                false
            );
            return Optional.of(currentSolution);
        }

        final LinearSolver.Result optimized = new LinearSolver(
            optimizationRecipes,
            relevantResources,
            startingResources,
            target,
            unconstrainedResources,
            Set.of(),
            options,
            cancellationToken
        ).minimizeTotalDeficitWithFloor(deficitResources, currentSolution.finalInventoryValues());

        if (optimized == null) {
            LOGGER.info(
                    "[LP] optimizeDeficitResources: totalDeficitBefore={} totalDeficitAfter={} "
                        + "deficitDecreased={} (no feasible optimized solution)",
                before,
                before,
                false
            );
            return Optional.empty();
        }

        final ResourcePool optimizedRequired = computeRequiredBaseItems(
            deficitResources,
            startingResources,
            target,
            optimized
        );
        final long after = totalDeficit(optimizedRequired);
        final boolean decreased = after < before;
        LOGGER.info(
                "[LP] optimizeDeficitResources: deficitResourceCount={} totalDeficitBefore={} "
                    + "totalDeficitAfter={} deficitDecreased={}",
            deficitResources.size(),
            before,
            after,
            decreased
        );
        return Optional.of(optimized);
    }

    private static ResourcePool computeRequiredBaseItems(
        final Set<MultiResourceKey> deficitResources,
        final ResourcePool startingResources,
        final ResourcePool target,
        final LinearSolver.Result solution
    ) {
        final ResourcePool required = new ResourcePool();
        for (final MultiResourceKey resource : deficitResources) {
            final long finalInventory = solution == null
                ? startingResources.getAmount(resource)
                : solution.finalInventoryValues().getAmount(resource);
            final long needed = Math.max(0L, target.getAmount(resource) - finalInventory);
            if (needed > 0L) {
                required.addAmount(resource, needed);
            }
        }
        return required;
    }

    private static long totalDeficit(final ResourcePool requiredBaseItems) {
        long total = 0L;
        for (final Map.Entry<MultiResourceKey, Long> entry : requiredBaseItems) {
            if (entry.getValue() > 0L) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private static List<SanitizedRecipe> reverseRecipePrioritiesForDeficitAnalysis(final List<SanitizedRecipe> recipes) {
        return recipes.stream()
            .map(recipe -> new SanitizedRecipe(
                recipe.recipeId(),
                recipe.sourcePatternId(),
                recipe.input(),
                recipe.output(),
                ~recipe.priority(),
                -recipe.insertionOrder()
            ))
            .toList();
    }

    private Optional<RecipeApplicationPath> buildRecipeApplicationPath(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final DeficitAnalysisResult deficitAnalysis
    ) {
        if (deficitAnalysis.solution().isEmpty()) {
            if (deficitAnalysis.requiredBaseItems().isEmpty()) {
                return Optional.empty();
            }

            final RecipeApplicationSet applicationSet = new RecipeApplicationSet(
                recipes,
                Map.of(),
                ResourcePool.empty(),
                startingResources.copy(),
                deficitAnalysis.requiredBaseItems(),
                RecipeAnalyzer.collectRelevantResourceKeys(recipes).stream()
                    .sorted(Comparator.comparing(Object::toString))
                    .toList()
            );
            return Optional.of(new RecipeApplicationPath(applicationSet, List.of(), ResourcePool.empty(), false));
        }

        final LinearSolver.Result solution = deficitAnalysis.solution().get();
        final Map<UUID, SanitizedRecipe> recipeById = new LinkedHashMap<>();
        for (final SanitizedRecipe recipe : recipes) {
            recipeById.put(recipe.recipeId(), recipe);
        }

        final Map<SanitizedRecipe, Long> valuesByRecipe = new LinkedHashMap<>();
        for (final Map.Entry<UUID, Long> entry : solution.recipeValues().entrySet()) {
            final SanitizedRecipe recipe = recipeById.get(entry.getKey());
            if (recipe != null && entry.getValue() > 0L) {
                valuesByRecipe.put(recipe, entry.getValue());
            }
        }

        final RecipeApplicationSet applicationSet = new RecipeApplicationSet(
            recipes,
            valuesByRecipe,
            computeUsedResources(valuesByRecipe),
            solution.finalInventoryValues(),
            deficitAnalysis.requiredBaseItems(),
            RecipeAnalyzer.collectRelevantResourceKeys(recipes).stream()
                .sorted(Comparator.comparing(Object::toString))
                .toList()
        );

        return ExecutionPlanner.buildRecipeApplicationPathFromApplicationSet(
            applicationSet,
            startingResources,
            cancellationToken
        );
    }

    private CycleEliminationResult findRecipeApplicationPlanViaCycleEliminationInternal(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final ResourcePool target
    ) {
        final ArrayDeque<Set<UUID>> attempts = new ArrayDeque<>();
        attempts.push(Set.of());

        final Set<List<UUID>> visited = new LinkedHashSet<>();
        visited.add(List.of());

        Set<UUID> bestFallbackDisabledRecipeIds = Set.of();
        int exploredBranches = 0;

        while (!attempts.isEmpty() && exploredBranches < options.maxCycleEliminationBranches()) {
            throwIfCancelled(loopSnippingCancellationToken, "LP loop snipping cancelled");
            final Set<UUID> disabledRecipeIds = attempts.pop();
            exploredBranches++;

            final Optional<LinearSolver.Result> solution = solveWithDisabledRecipes(
                recipes,
                startingResources,
                target,
                disabledRecipeIds
            );
            if (solution.isEmpty()) {
                bestFallbackDisabledRecipeIds = keepLargerSet(bestFallbackDisabledRecipeIds, disabledRecipeIds);
                continue;
            }

            final DeficitAnalysisResult noDeficits =
                new DeficitAnalysisResult(ResourcePool.empty(), Optional.of(solution.get()));
            final Optional<RecipeApplicationPath> candidatePath =
                buildRecipeApplicationPath(recipes, startingResources, noDeficits);
            if (candidatePath.isPresent()) {
                return new CycleEliminationResult(candidatePath, Set.of());
            }

            final List<SanitizedRecipe> usedRecipes = recipes.stream()
                .filter(recipe -> solution.get().recipeValues().getOrDefault(recipe.recipeId(), 0L) > 0)
                .toList();
            final RecipeAnalyzer.CycleDetectionResult cycleDetectionResult =
                RecipeAnalyzer.detectRecipeCycles(usedRecipes);
            if (cycleDetectionResult.cycles().isEmpty()) {
                bestFallbackDisabledRecipeIds = keepLargerSet(bestFallbackDisabledRecipeIds, disabledRecipeIds);
                continue;
            }

            enqueueCycleBreakAttempts(
                cycleDetectionResult.cycles(),
                solution.get(),
                disabledRecipeIds,
                visited,
                attempts
            );
        }

        return new CycleEliminationResult(Optional.empty(), Set.copyOf(bestFallbackDisabledRecipeIds));
    }

    private Optional<LinearSolver.Result> solveWithDisabledRecipes(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final ResourcePool target,
        final Set<UUID> disabledRecipeIds
    ) {
        throwIfCancelled(loopSnippingCancellationToken, "LP loop snipping cancelled");
        final Set<MultiResourceKey> relevantResources = new LinkedHashSet<>(
            RecipeAnalyzer.collectRelevantResourceKeys(recipes)
        );
        relevantResources.addAll(target.resourceKeys());

        final LinearSolver.Result result = new LinearSolver(
            recipes,
            relevantResources,
            startingResources,
            target,
            Set.of(),
            disabledRecipeIds,
            options,
            loopSnippingCancellationToken
        ).lexicographicMinimum();
        return Optional.ofNullable(result);
    }

    private static ResourcePool computeUsedResources(final Map<SanitizedRecipe, Long> recipeValues) {
        final ResourcePool used = new ResourcePool();
        for (final Map.Entry<SanitizedRecipe, Long> entry : recipeValues.entrySet()) {
            final SanitizedRecipe recipe = entry.getKey();
            final long times = entry.getValue();
            for (final Map.Entry<MultiResourceKey, Long> input : recipe.input()) {
                used.addAmount(input.getKey(), input.getValue() * times);
            }
        }
        return used;
    }

    private static int countResources(final ResourcePool pool) {
        int count = 0;
        for (final Map.Entry<MultiResourceKey, Long> entry : pool) {
            if (entry.getValue() > 0L) {
                count++;
            }
        }
        return count;
    }

    private static long totalAmount(final ResourcePool pool) {
        long total = 0L;
        for (final Map.Entry<MultiResourceKey, Long> entry : pool) {
            if (entry.getValue() > 0L) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private static void validateInputs(
        final List<SanitizedRecipe> recipes,
        final ResourcePool startingResources,
        final ResourcePool target
    ) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(startingResources, "startingResources cannot be null");
        Objects.requireNonNull(target, "target cannot be null");
    }

    private void throwIfCancelled() {
        throwIfCancelled(cancellationToken, "LP solver cancelled");
    }

    private static void throwIfCancelled(final CancellationToken token, final String message) {
        if (token.isCancelled()) {
            throw new CancellationException(message);
        }
    }

    private static Set<UUID> keepLargerSet(final Set<UUID> currentBest, final Set<UUID> candidate) {
        if (candidate.size() > currentBest.size()) {
            return Set.copyOf(candidate);
        }
        return currentBest;
    }

    private static void enqueueCycleBreakAttempts(
        final List<List<UUID>> cycles,
        final LinearSolver.Result solution,
        final Set<UUID> disabledRecipeIds,
        final Set<List<UUID>> visited,
        final ArrayDeque<Set<UUID>> attempts
    ) {
        for (final List<UUID> cycle : cycles) {
            final Optional<UUID> recipeToDisable = cycle.stream()
                .max(Comparator.comparingLong(id -> solution.recipeValues().getOrDefault(id, 0L)));
            if (recipeToDisable.isEmpty()) {
                continue;
            }
            addAttemptIfUnseen(disabledRecipeIds, recipeToDisable.get(), visited, attempts);
        }
    }

    private static void addAttemptIfUnseen(
        final Set<UUID> disabledRecipeIds,
        final UUID recipeIdToDisable,
        final Set<List<UUID>> visited,
        final ArrayDeque<Set<UUID>> attempts
    ) {
        if (disabledRecipeIds.contains(recipeIdToDisable)) {
            return;
        }

        final Set<UUID> nextDisabledRecipeIds = new LinkedHashSet<>(disabledRecipeIds);
        nextDisabledRecipeIds.add(recipeIdToDisable);
        final List<UUID> key = nextDisabledRecipeIds.stream().sorted().toList();
        if (visited.add(key)) {
            attempts.push(Set.copyOf(nextDisabledRecipeIds));
        }
    }

    private record CycleEliminationResult(
        Optional<RecipeApplicationPath> recipeApplicationResult,
        Set<UUID> fallbackDisabledRecipeIds
    ) {
        private CycleEliminationResult {
            Objects.requireNonNull(recipeApplicationResult, "recipeApplicationResult cannot be null");
            Objects.requireNonNull(fallbackDisabledRecipeIds, "fallbackDisabledRecipeIds cannot be null");
            fallbackDisabledRecipeIds = Set.copyOf(fallbackDisabledRecipeIds);
        }
    }

    private record DeficitAnalysisResult(ResourcePool requiredBaseItems, Optional<LinearSolver.Result> solution) {
        private DeficitAnalysisResult {
            Objects.requireNonNull(requiredBaseItems, "requiredBaseItems cannot be null");
            Objects.requireNonNull(solution, "solution cannot be null");
            requiredBaseItems = requiredBaseItems.copy();
        }
    }
}
