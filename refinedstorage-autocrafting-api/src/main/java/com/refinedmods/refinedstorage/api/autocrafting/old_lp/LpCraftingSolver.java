package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayDeque;
import java.util.Collection;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LpCraftingSolver {
    // Solves crafting problems with linear programming and execution-plan validation.

    private static final Logger LOGGER = LoggerFactory.getLogger(LpCraftingSolver.class);
    private static final PlanningOutcome CANCELLED_PLANNING_OUTCOME = new PlanningOutcome(
        0,
        Optional.empty(),
        LpResourceSet.empty(),
        Set.of()
    );
    private final LpSolverOptions options;
    private final CancellationToken cancellationToken;

    public LpCraftingSolver() {
        this(LpSolverOptions.defaults(), CancellationToken.NONE);
    }

    public LpCraftingSolver(final CancellationToken cancellationToken) {
        this(LpSolverOptions.defaults(), cancellationToken);
    }

    public LpCraftingSolver(final LpSolverOptions options) {
        this(options, CancellationToken.NONE);
    }

    public LpCraftingSolver(final LpSolverOptions options, final CancellationToken cancellationToken) {
        this.options = Objects.requireNonNull(options, "options cannot be null");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
    }

    public PlanningOutcome solve(final List<LpPatternRecipe> recipes,
                                 final LpResourceSet startingResources,
                                 final LpResourceSet target) {
        validateInputs(recipes, startingResources, target);
        try {
            throwIfCancelled();
            LOGGER.info("[LP] solve() called. Recipes: {}, Target: {}", recipes.size(), target);
            final long maxCraftableAmount = computeMaxCraftableTargetAmount(
                recipes, startingResources, target
            );
            throwIfCancelled();
            LOGGER.info("[LP] Max craftable amount for target {}: {}", target, maxCraftableAmount);
            if (maxCraftableAmount == 0) {
                LOGGER.info("[LP] No craftable amount possible for target {}.", target);
                final DeficitAnalysisResult deficitAnalysis =
                    computeRequiredBaseItemsAndSolution(recipes, startingResources, target);
                throwIfCancelled();
                return new PlanningOutcome(
                    maxCraftableAmount,
                    buildRecipeApplicationPlanResult(recipes, startingResources, deficitAnalysis),
                    deficitAnalysis.requiredBaseItems(),
                    Set.of()
                );
            }

            final CycleEliminationResult cycleEliminationResult = findRecipeApplicationPlanViaCycleEliminationInternal(
                recipes,
                startingResources,
                target
            );
            throwIfCancelled();
            if (cycleEliminationResult.recipeApplicationResult().isPresent()) {
                LOGGER.info("[LP] Found executable plan for target {}.", target);
                return new PlanningOutcome(
                    maxCraftableAmount,
                    cycleEliminationResult.recipeApplicationResult(),
                    LpResourceSet.empty(),
                    Set.of()
                );
            }

            final Set<UUID> disabledRecipeIds = cycleEliminationResult.fallbackDisabledRecipeIds();
            LOGGER.info("[LP] No executable plan found, fallback disabled recipes: {}", disabledRecipeIds);
            final List<LpPatternRecipe> reducedRecipes = recipes.stream()
                .filter(recipe -> !disabledRecipeIds.contains(recipe.uniqueId()))
                .map(LpPatternRecipe::copy)
                .toList();
            final DeficitAnalysisResult deficitAnalysis =
                computeRequiredBaseItemsAndSolution(reducedRecipes, startingResources, target);
            throwIfCancelled();
            return new PlanningOutcome(
                maxCraftableAmount,
                buildRecipeApplicationPlanResult(reducedRecipes, startingResources, deficitAnalysis),
                deficitAnalysis.requiredBaseItems(),
                disabledRecipeIds
            );
        } catch (final CancellationException e) {
            LOGGER.info("[LP] solve() cancelled.");
            return CANCELLED_PLANNING_OUTCOME;
        }
    }

    private long computeMaxCraftableTargetAmount(
        final List<LpPatternRecipe> recipes,
        final LpResourceSet startingResources,
        final LpResourceSet target
    ) {
        validateInputs(recipes, startingResources, target);
        if (target.isEmpty()) {
            LOGGER.info("[LP] Target is empty, cannot compute max craftable amount.");
            return 0;
        }
        LOGGER.info("[LP] Pruning recipes");
        final List<LpPatternRecipe> prioritizedRecipes =
            LpRecipeAnalysis.prioritizeAndPruneRelevantRecipes(copyRecipes(recipes), target);
        LOGGER.info("[LP] Getting relevant keys");
        final Set<ResourceKey> relevantResources = new LinkedHashSet<>(
            LpRecipeAnalysis.collectRelevantResourceKeys(prioritizedRecipes)
        );
        relevantResources.addAll(target.resourceKeys());

        final ResourceKey targetResource = target.resourceKeys().iterator().next();
        LOGGER.info("[LP] Maximizing target {}", targetResource);
        final FlowSearchResult result = new FlowSearchModel(
            prioritizedRecipes,
            relevantResources,
            startingResources,
            target,
            relevantResources,
            Set.of(),
            options
        ).maximize(targetResource);
        if (result == null) {
            LOGGER.info("[LP] No feasible solution found for maximizing target {}.", targetResource);
            return 0;
        }
        LOGGER.info("[LP] Calculated max craftable amount for target {}.", targetResource);

        final long maxCraftableAmount = Math.max(
            0L,
            result.finalInventoryValues().getAmount(targetResource)
                - startingResources.getAmount(targetResource)
        );
        LOGGER.info("[LP] Max craftable amount for {}: {}", targetResource, maxCraftableAmount);
        return maxCraftableAmount;
    }

    public LpResourceSet computeRequiredBaseItems(final List<LpPatternRecipe> recipes,
                                                  final LpResourceSet startingResources,
                                                  final LpResourceSet target) {
        validateInputs(recipes, startingResources, target);
        try {
            throwIfCancelled();
            return computeRequiredBaseItemsAndSolution(recipes, startingResources, target).requiredBaseItems();
        } catch (final CancellationException e) {
            LOGGER.info("[LP] computeRequiredBaseItems() cancelled.");
            return LpResourceSet.empty();
        }
    }

    private DeficitAnalysisResult computeRequiredBaseItemsAndSolution(final List<LpPatternRecipe> recipes,
                                                                      final LpResourceSet startingResources,
                                                                      final LpResourceSet target) {
        validateInputs(recipes, startingResources, target);
        throwIfCancelled();

        final List<LpPatternRecipe> prioritizedRecipes =
            LpRecipeAnalysis.prioritizeAndPruneRelevantRecipes(copyRecipes(recipes), target);
        final List<LpPatternRecipe> selectedRecipes =
            LpRecipeAnalysis.selectTopPriorityRecipesPerOutputResource(prioritizedRecipes);
        final Set<ResourceKey> relevantResources = 
            LpRecipeAnalysis.collectRelevantResourceKeys(selectedRecipes);
        relevantResources.addAll(target.resourceKeys());

        final Set<ResourceKey> deficitResources = new LinkedHashSet<>(
            LpRecipeAnalysis.collectNonProducibleResources(selectedRecipes, relevantResources)
        );
        deficitResources.addAll(
            LpRecipeAnalysis.collectLoopEntryDeficitResourcesOnTargetBranches(selectedRecipes, target)
        );

        if (selectedRecipes.isEmpty()) {
            final LpResourceSet required = new LpResourceSet();
            for (final ResourceKey resource : deficitResources) {
                throwIfCancelled();
                final long needed = Math.max(
                    0L,
                    target.getAmount(resource) - startingResources.getAmount(resource)
                );
                if (needed > 0) {
                    required.addAmount(resource, needed);
                }
            }
            return new DeficitAnalysisResult(required, Optional.empty());
        }

        final Set<ResourceKey> constrainedResources = new LinkedHashSet<>(relevantResources);
        constrainedResources.removeAll(deficitResources);
        constrainedResources.addAll(
            target.resourceKeys().stream()
                .filter(resource -> !deficitResources.contains(resource))
                .toList()
        );

        final FlowSearchResult result = new FlowSearchModel(
            selectedRecipes,
            relevantResources,
            startingResources,
            target,
            constrainedResources,
            Set.of(),
            options,
            cancellationToken
        ).lexicographicMinimum();

        final LpResourceSet required = new LpResourceSet();
        for (final ResourceKey resource : deficitResources) {
            throwIfCancelled();
            final long finalInventory = result == null
                ? startingResources.getAmount(resource)
                : result.finalInventoryValues().getAmount(resource);
            final long needed = Math.max(0L, target.getAmount(resource) - finalInventory);
            if (needed > 0) {
                required.addAmount(resource, needed);
            }
        }
        final Optional<LpCraftingSolution> solution = result == null
            ? Optional.empty()
            : Optional.of(new LpCraftingSolution(
                result.recipeValues(),
                result.finalInventoryValues(),
                relevantResources.stream().sorted(Comparator.comparing(Object::toString)).toList()
            ));
        return new DeficitAnalysisResult(required, solution);
    }

    private Optional<RecipeApplicationPlanResult> buildRecipeApplicationPlanResult(
        final List<LpPatternRecipe> recipes,
        final LpResourceSet startingResources,
        final DeficitAnalysisResult deficitAnalysis
    ) {
        if (deficitAnalysis.solution().isEmpty()) {
            return Optional.empty();
        }
        final LpCraftingSolution solution = deficitAnalysis.solution().get();
        final List<LpExecutionPlanStep> plan = LpExecutionPlanner.buildRecipeApplicationPlanFromRecipeUsage(
            recipes,
            solution.recipeValues(),
            startingResources,
            cancellationToken
        );
        return Optional.of(new RecipeApplicationPlanResult(solution, plan, deficitAnalysis.requiredBaseItems()));
    }

    public CycleEliminationResult findRecipeApplicationPlanViaCycleElimination(final List<LpPatternRecipe> recipes,
                                                                            final LpResourceSet startingResources,
                                                                            final LpResourceSet target) {
        validateInputs(recipes, startingResources, target);
        try {
            throwIfCancelled();
            return findRecipeApplicationPlanViaCycleEliminationInternal(recipes, startingResources, target);
        } catch (final CancellationException e) {
            LOGGER.info("[LP] findRecipeApplicationPlanViaCycleElimination() cancelled.");
            return new CycleEliminationResult(Optional.empty(), Set.of());
        }
    }

    private CycleEliminationResult findRecipeApplicationPlanViaCycleEliminationInternal(
        final List<LpPatternRecipe> recipes,
        final LpResourceSet startingResources,
        final LpResourceSet target
    ) {
        validateInputs(recipes, startingResources, target);
        LOGGER.info("[LP] Starting cycle elimination for {} recipes.", recipes.size());

        final ArrayDeque<Set<UUID>> attempts = new ArrayDeque<>();
        attempts.push(Set.of());

        final Set<List<UUID>> visited = new LinkedHashSet<>();
        visited.add(List.of());

        Set<UUID> bestFallbackDisabledRecipeIds = Set.of();
        int exploredBranches = 0;

        while (!attempts.isEmpty() && exploredBranches < options.maxCycleEliminationBranches()) {
            throwIfCancelled();
            final Set<UUID> disabledRecipeIds = attempts.pop();
            exploredBranches++;
            LOGGER.info("[LP] Cycle elimination attempt {}: disabledRecipeIds={}", exploredBranches, disabledRecipeIds);

            final Optional<LpCraftingSolution> solution = solveWithDisabledRecipes(
                recipes,
                startingResources,
                target,
                disabledRecipeIds
            );
            if (solution.isEmpty()) {
                LOGGER.info("[LP] No solution found with disabledRecipeIds={}", disabledRecipeIds);
                bestFallbackDisabledRecipeIds = keepLargerSet(bestFallbackDisabledRecipeIds, disabledRecipeIds);
                continue;
            }

            final Optional<List<LpExecutionPlanStep>> plan = LpExecutionPlanner.buildExecutablePlanFromRecipeUsage(
                recipes,
                solution.get().recipeValues(),
                startingResources,
                cancellationToken
            );
            if (plan.isPresent()) {
                LOGGER.info("[LP] Found executable plan after {} cycle elimination attempts.", exploredBranches);
                return new CycleEliminationResult(
                    Optional.of(new RecipeApplicationPlanResult(solution.get(), plan.get(), LpResourceSet.empty())),
                    Set.of()
                );
            }

            final List<LpPatternRecipe> usedRecipes = recipes.stream()
                .filter(recipe -> solution.get().recipeUsageCount(recipe) > 0)
                .map(LpPatternRecipe::copy)
                .toList();
            final LpRecipeAnalysis.CycleDetectionResult cycleDetectionResult =
                LpRecipeAnalysis.detectRecipeCycles(usedRecipes);
            if (cycleDetectionResult.cycles().isEmpty()) {
                LOGGER.info("[LP] No cycles detected in used recipes.");
                bestFallbackDisabledRecipeIds = keepLargerSet(bestFallbackDisabledRecipeIds, disabledRecipeIds);
                continue;
            }

            LOGGER.info("[LP] Cycles detected: {}. Enqueuing break attempts.", cycleDetectionResult.cycles().size());
            enqueueCycleBreakAttempts(
                cycleDetectionResult.cycles(),
                solution.get(),
                disabledRecipeIds,
                visited,
                attempts
            );
        }

        LOGGER.info("[LP] Cycle elimination exhausted. No executable plan found. Returning best fallback.");
        return new CycleEliminationResult(Optional.empty(), Set.copyOf(bestFallbackDisabledRecipeIds));
    }

    private static Set<UUID> keepLargerSet(final Set<UUID> currentBest,
                                           final Set<UUID> candidate) {
        if (candidate.size() > currentBest.size()) {
            return Set.copyOf(candidate);
        }
        return currentBest;
    }

    private static void enqueueCycleBreakAttempts(final List<List<LpPatternRecipe>> cycles,
                                                  final LpCraftingSolution solution,
                                                  final Set<UUID> disabledRecipeIds,
                                                  final Set<List<UUID>> visited,
                                                  final ArrayDeque<Set<UUID>> attempts) {
        // For each cycle, identifies the recipe with the largest usage count in the solution
        // and creates a new attempt with that recipe disabled, if it hasn't been attempted before.
        for (final List<LpPatternRecipe> cycle : cycles) {
            final Optional<LpPatternRecipe> recipeToDisable = cycle.stream()
                .max(Comparator.comparingLong(solution::recipeUsageCount));
            if (recipeToDisable.isEmpty()) {
                continue;
            }
            addAttemptIfUnseen(
                disabledRecipeIds,
                recipeToDisable.get().uniqueId(),
                visited,
                attempts
            );
        }
    }

    private static void addAttemptIfUnseen(final Set<UUID> disabledRecipeIds,
                                           final UUID recipeIdToDisable,
                                           final Set<List<UUID>> visited,
                                           final ArrayDeque<Set<UUID>> attempts) {
        // Checks if disabling the given recipe ID has already been attempted. 
        // If not, creates a new set of disabled recipe IDs with it added and pushes it onto the attempts stack.
        if (disabledRecipeIds.contains(recipeIdToDisable)) {
            return;
        }

        final Set<UUID> nextDisabledRecipeIds = new LinkedHashSet<>(disabledRecipeIds);
        nextDisabledRecipeIds.add(recipeIdToDisable);
        final List<UUID> key = nextDisabledRecipeIds.stream().sorted().toList();
        if (visited.add(key)) {
            attempts.push(Set.copyOf(nextDisabledRecipeIds));
            return;
        }
    }

    private Optional<LpCraftingSolution> solveWithDisabledRecipes(final List<LpPatternRecipe> recipes,
                                                                  final LpResourceSet startingResources,
                                                                  final LpResourceSet target,
                                                                  final Set<UUID> disabledRecipeIds) {
        // Solves the crafting problem with the given set of disabled recipe IDs, returning an optional solution.
        LOGGER.info("[LP] Solving with disabled recipes: {}", disabledRecipeIds);
        throwIfCancelled();
        final List<LpPatternRecipe> prioritizedRecipes =
            LpRecipeAnalysis.prioritizeAndPruneRelevantRecipes(copyRecipes(recipes), target);
        final Set<ResourceKey> relevantResources = new LinkedHashSet<>(
            LpRecipeAnalysis.collectRelevantResourceKeys(prioritizedRecipes)
        );
        relevantResources.addAll(target.resourceKeys());

        final FlowSearchResult result = new FlowSearchModel(
            prioritizedRecipes,
            relevantResources,
            startingResources,
            target,
            relevantResources,
            disabledRecipeIds,
            options,
            cancellationToken
        ).lexicographicMinimum();
        if (result == null) {
            LOGGER.info("[LP] No feasible solution found with disabled recipes: {}", disabledRecipeIds);
            return Optional.empty();
        }

        final List<ResourceKey> sortedRelevantResources = relevantResources.stream()
            .sorted(Comparator.comparing(Object::toString))
            .toList();
        return Optional.of(new LpCraftingSolution(
            result.recipeValues(),
            result.finalInventoryValues(),
            sortedRelevantResources
        ));
    }

    private static List<LpPatternRecipe> copyRecipes(final Collection<LpPatternRecipe> recipes) {
        return recipes.stream().map(LpPatternRecipe::copy).toList();
    }

    private static void validateInputs(final List<LpPatternRecipe> recipes,
                                       final LpResourceSet startingResources,
                                       final LpResourceSet target) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(startingResources, "startingResources cannot be null");
        Objects.requireNonNull(target, "target cannot be null");
    }

    private void throwIfCancelled() {
        if (cancellationToken.isCancelled()) {
            throw new CancellationException("LP solver cancelled");
        }
    }

    public record RecipeApplicationPlanResult(LpCraftingSolution solution,
                                              List<LpExecutionPlanStep> plan,
                                              LpResourceSet requiredBaseItems) {
        public RecipeApplicationPlanResult {
            Objects.requireNonNull(solution, "solution cannot be null");
            Objects.requireNonNull(requiredBaseItems, "requiredBaseItems cannot be null");
            plan = List.copyOf(plan);
            requiredBaseItems = requiredBaseItems.copy();
        }
    }

    private record DeficitAnalysisResult(LpResourceSet requiredBaseItems,
                                         Optional<LpCraftingSolution> solution) {
        private DeficitAnalysisResult {
            Objects.requireNonNull(requiredBaseItems, "requiredBaseItems cannot be null");
            Objects.requireNonNull(solution, "solution cannot be null");
            requiredBaseItems = requiredBaseItems.copy();
        }
    }

    public record CycleEliminationResult(Optional<RecipeApplicationPlanResult> recipeApplicationResult,
                                         Set<UUID> fallbackDisabledRecipeIds) {
        public CycleEliminationResult {
            Objects.requireNonNull(recipeApplicationResult, "recipeApplicationResult cannot be null");
            Objects.requireNonNull(fallbackDisabledRecipeIds, "fallbackDisabledRecipeIds cannot be null");
            fallbackDisabledRecipeIds = Set.copyOf(fallbackDisabledRecipeIds);
        }
    }

    public record PlanningOutcome(long maxCraftableAmount,
                                  Optional<RecipeApplicationPlanResult> recipeApplicationResult,
                                  LpResourceSet requiredBaseItems,
                                  Set<UUID> fallbackDisabledRecipeIds) {
        public PlanningOutcome {
            Objects.requireNonNull(recipeApplicationResult, "recipeApplicationResult cannot be null");
            Objects.requireNonNull(requiredBaseItems, "requiredBaseItems cannot be null");
            Objects.requireNonNull(fallbackDisabledRecipeIds, "fallbackDisabledRecipeIds cannot be null");
            fallbackDisabledRecipeIds = Set.copyOf(fallbackDisabledRecipeIds);
        }
    }

    private static final class FlowSearchModel {
        private static final Logger LOGGER = LoggerFactory.getLogger(FlowSearchModel.class);
        private static final long WAIT_SLICE_MILLIS = 250L;
        private final List<LpPatternRecipe> recipes;
        private final List<LpPatternRecipe> reversePriorityRecipes;
        private final Set<ResourceKey> relevantResources;
        private final LpResourceSet startingResources;
        private final LpResourceSet target;
        private final Set<ResourceKey> constrainedResources;
        private final Set<UUID> disabledRecipeIds;
        private final LpSolverOptions options;
        private final CancellationToken cancellationToken;

        private FlowSearchModel(final List<LpPatternRecipe> recipes,
                                final Set<ResourceKey> relevantResources,
                                final LpResourceSet startingResources,
                                final LpResourceSet target,
                                final Set<ResourceKey> constrainedResources,
                                final Set<UUID> disabledRecipeIds,
                                final LpSolverOptions options) {
            this(
                recipes,
                relevantResources,
                startingResources,
                target,
                constrainedResources,
                disabledRecipeIds,
                options,
                CancellationToken.NONE
            );
        }

        private FlowSearchModel(final List<LpPatternRecipe> recipes,
                                final Set<ResourceKey> relevantResources,
                                final LpResourceSet startingResources,
                                final LpResourceSet target,
                                final Set<ResourceKey> constrainedResources,
                                final Set<UUID> disabledRecipeIds,
                                final LpSolverOptions options,
                                final CancellationToken cancellationToken) {
            this.recipes = List.copyOf(recipes);
            this.reversePriorityRecipes = recipes.stream()
                .sorted(Comparator
                    .comparing((LpPatternRecipe recipe) -> recipe.effectivePriority() == null
                        ? Integer.MIN_VALUE
                        : recipe.effectivePriority())
                    .thenComparing(LpPatternRecipe::uniqueId))
                .toList();
            this.relevantResources = Set.copyOf(relevantResources);
            this.startingResources = startingResources.copy();
            this.target = target.copy();
            this.constrainedResources = Set.copyOf(constrainedResources);
            this.disabledRecipeIds = Set.copyOf(disabledRecipeIds);
            this.options = options;
            this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        }

        private FlowSearchResult lexicographicMinimum() {
            LOGGER.info("[LP] Starting lexicographic minimum search with disabled recipes: {}", disabledRecipeIds);
            throwIfCancelled();
            final FlowSearchResult feasibilityResult = solveWithObjective(null, null, false, Map.of());
            LOGGER.info("[LP] Feasibility check result: {}", feasibilityResult == null ? "infeasible" : "feasible");
            if (feasibilityResult == null) {
                return null;
            }

            final Map<UUID, Long> lockedRecipeValues = new LinkedHashMap<>();
            for (final LpPatternRecipe recipe : reversePriorityRecipes) {
                throwIfCancelled();
                final FlowSearchResult result = solveWithObjective(null, recipe.uniqueId(), false, lockedRecipeValues);
                Objects.requireNonNull(
                    result,
                    "Expected lexicographic lock step to remain feasible"
                );
                LOGGER.info("[LP] Lexicographic lock step result for recipe {}: {}", recipe.uniqueId(), result);
                lockedRecipeValues.put(recipe.uniqueId(), result.recipeValues().getOrDefault(recipe.uniqueId(), 0L));
            }
            final FlowSearchResult finalResult = solveWithObjective(null, null, false, lockedRecipeValues);
            LOGGER.info("[LP] Final lexicographic minimum result: {}", finalResult);
            return finalResult;
        }

        private FlowSearchResult maximize(final ResourceKey objectiveResource) {
            throwIfCancelled();
            final FlowSearchResult result = solveWithObjective(
                Objects.requireNonNull(objectiveResource, "objectiveResource cannot be null"),
                null,
                true,
                Map.of()
            );
            return result;
        }

        private FlowSearchResult solveWithObjective(final ResourceKey objectiveResource,
                                                    final UUID objectiveRecipeId,
                                                    final boolean maximize,
                                                    final Map<UUID, Long> lockedRecipeValues) {
            throwIfCancelled();
            LOGGER.info(
                "[LP] Solving with objectiveResource={}, objectiveRecipeId={}, maximize={}, "
                    + "lockedRecipeValues={}",
                objectiveResource, objectiveRecipeId, maximize, lockedRecipeValues);

            final FutureTask<FlowSearchResult> solveTask = new FutureTask<>(
                () -> solveWithObjectiveInternal(objectiveResource, objectiveRecipeId, maximize, lockedRecipeValues)
            );
            final Thread solveThread = new Thread(solveTask, "lp-ojalgo-solve");
            solveThread.setDaemon(true);
            solveThread.start();

            try {
                return awaitNonCooperativeTask(
                    solveTask,
                    solveThread,
                    "solveWithObjective",
                    true,
                    objectiveResource,
                    objectiveRecipeId,
                    maximize,
                    lockedRecipeValues
                );
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                cancellationToken.cancel();
                solveThread.interrupt();
                throw new CancellationException("Interrupted in solveWithObjective");
            } catch (final ExecutionException e) {
                final Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalStateException("Failed in solveWithObjective", cause);
            }
        }

        private FlowSearchResult solveWithObjectiveInternal(final ResourceKey objectiveResource,
                                                            final UUID objectiveRecipeId,
                                                            final boolean maximize,
                                                            final Map<UUID, Long> lockedRecipeValues) {
            throwIfCancelled();
            LOGGER.info(
                "[LP] Entered solveWithObjectiveInternal on thread {}",
                Thread.currentThread().getName()
            );

            final ExpressionsBasedModel model = createModelWithDiagnostics();

            LOGGER.info("[LP] Creating recipe vars");
            final Map<UUID, Variable> variableByRecipeId = createRecipeVariables(model);
            LOGGER.info(
                "[LP] Created model with {} variables for objectiveResource={}, objectiveRecipeId={}, "
                    + "maximize={}, lockedRecipeValues={}",
                variableByRecipeId.size(),
                objectiveResource,
                objectiveRecipeId,
                maximize,
                lockedRecipeValues
            );
            configureObjective(model, variableByRecipeId, objectiveResource, objectiveRecipeId);
            LOGGER.info("[LP] Adding constraints");
            addResourceConstraints(model, variableByRecipeId);
            LOGGER.info("[LP] Adding recipe locks");
            addRecipeLocks(model, variableByRecipeId, lockedRecipeValues);
            throwIfCancelled();

            LOGGER.info(
                "[LP] Solving model with objectiveResource={}, objectiveRecipeId={}, maximize={}, "
                    + "lockedRecipeValues={}",
                objectiveResource,
                objectiveRecipeId,
                maximize,
                lockedRecipeValues
            );
            final Optimisation.Result result = maximize ? model.maximise() : model.minimise();
            LOGGER.info("[LP] Solver result: {}", result);
            if (!result.getState().isFeasible()) {
                return null;
            }

            final Map<UUID, Long> recipeValues = extractUsedRecipeValues(variableByRecipeId);

            final LpResourceSet finalInventoryValues = computeFinalInventoryValues(recipeValues);

            return new FlowSearchResult(
                Map.copyOf(recipeValues),
                finalInventoryValues.copy()
            );
        }

        private ExpressionsBasedModel createModelWithDiagnostics() {
            throwIfCancelled();
            final ClassLoader classLoader = ExpressionsBasedModel.class.getClassLoader();
            final String classLoaderName = classLoader == null ? "bootstrap" : classLoader.toString();
            final String codeSource = ExpressionsBasedModel.class.getProtectionDomain().getCodeSource() == null
                ? "unknown"
                : String.valueOf(ExpressionsBasedModel.class.getProtectionDomain().getCodeSource().getLocation());

            LOGGER.info(
                "[LP] Creating ExpressionsBasedModel with classLoader={} codeSource={}",
                classLoaderName,
                codeSource
            );

            final FutureTask<ExpressionsBasedModel> task = new FutureTask<>(ExpressionsBasedModel::new);
            final Thread modelConstructionThread = new Thread(task, "lp-ojalgo-model-construction");
            modelConstructionThread.setDaemon(true);
            modelConstructionThread.start();

            try {
                return awaitNonCooperativeTask(
                    task,
                    modelConstructionThread,
                    "ExpressionsBasedModel construction",
                    false,
                    null,
                    null,
                    false,
                    Map.of()
                );
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                cancellationToken.cancel();
                modelConstructionThread.interrupt();
                throw new CancellationException("Interrupted while creating ExpressionsBasedModel");
            } catch (final ExecutionException e) {
                throw new IllegalStateException("Failed creating ExpressionsBasedModel", e.getCause());
            }
        }

        private <T> T awaitNonCooperativeTask(final FutureTask<T> task,
                                              final Thread workerThread,
                                              final String description,
                                              final boolean logObjectiveDetails,
                                              final ResourceKey objectiveResource,
                                              final UUID objectiveRecipeId,
                                              final boolean maximize,
                                              final Map<UUID, Long> lockedRecipeValues)
            throws InterruptedException, ExecutionException {
            while (true) {
                throwIfCancelled();

                final long remainingMillis = cancellationToken.timeRemainingMillis();
                if (remainingMillis <= 0L) {
                    timeoutNonCooperativeTask(
                        workerThread,
                        description,
                        logObjectiveDetails,
                        objectiveResource,
                        objectiveRecipeId,
                        maximize,
                        lockedRecipeValues
                    );
                }

                final long waitMillis = remainingMillis == Long.MAX_VALUE
                    ? WAIT_SLICE_MILLIS
                    : Math.min(remainingMillis, WAIT_SLICE_MILLIS);
                try {
                    return task.get(waitMillis, TimeUnit.MILLISECONDS);
                } catch (final TimeoutException e) {
                    if (cancellationToken.timeRemainingMillis() <= 0L) {
                        timeoutNonCooperativeTask(
                            workerThread,
                            description,
                            logObjectiveDetails,
                            objectiveResource,
                            objectiveRecipeId,
                            maximize,
                            lockedRecipeValues
                        );
                    }
                }
            }
        }

        private void timeoutNonCooperativeTask(final Thread workerThread,
                                               final String description,
                                               final boolean logObjectiveDetails,
                                               final ResourceKey objectiveResource,
                                               final UUID objectiveRecipeId,
                                               final boolean maximize,
                                               final Map<UUID, Long> lockedRecipeValues) {
            cancellationToken.cancel();
            if (logObjectiveDetails) {
                LOGGER.error(
                    "[LP] Timed out {} due to cancellation deadline. objectiveResource={}, objectiveRecipeId={}, "
                        + "maximize={}, lockedRecipeValues={}",
                    description,
                    objectiveResource,
                    objectiveRecipeId,
                    maximize,
                    lockedRecipeValues
                );
            } else {
                LOGGER.error("[LP] Timed out {} due to cancellation deadline.", description);
            }
            workerThread.interrupt();
            throw new CancellationException("Timed out " + description);
        }

        private void throwIfCancelled() {
            if (cancellationToken.isCancelled()) {
                throw new CancellationException("LP solver cancelled");
            }
        }

        private Map<UUID, Variable> createRecipeVariables(final ExpressionsBasedModel model) {
            final Map<UUID, Variable> variableByRecipeId = new LinkedHashMap<>();
            for (final LpPatternRecipe recipe : recipes) {
                throwIfCancelled();
                final boolean disabled = disabledRecipeIds.contains(recipe.uniqueId());
                final Variable variable = model.addVariable(recipe.uniqueId().toString())
                    .integer(true)
                    .lower(0)
                    .upper(disabled ? 0 : options.recipeUpperBound());
                variableByRecipeId.put(recipe.uniqueId(), variable);
            }
            return variableByRecipeId;
        }

        private void configureObjective(final ExpressionsBasedModel model,
                                        final Map<UUID, Variable> variableByRecipeId,
                                        final ResourceKey objectiveResource,
                                        final UUID objectiveRecipeId) {
            throwIfCancelled();
            if (objectiveResource == null && objectiveRecipeId == null) {
                return;
            }

            final Expression objective = model.newExpression("objective").weight(1);
            for (final LpPatternRecipe recipe : recipes) {
                throwIfCancelled();
                final long coefficient = objectiveCoefficient(recipe, objectiveResource, objectiveRecipeId);
                if (coefficient != 0) {
                    objective.set(variableByRecipeId.get(recipe.uniqueId()), coefficient);
                }
            }
        }

        private long objectiveCoefficient(final LpPatternRecipe recipe,
                                          final ResourceKey objectiveResource,
                                          final UUID objectiveRecipeId) {
            if (objectiveRecipeId != null) {
                return recipe.uniqueId().equals(objectiveRecipeId) ? 1 : 0;
            }
            return recipe.coefficient(objectiveResource);
        }

        private void addResourceConstraints(final ExpressionsBasedModel model,
                                            final Map<UUID, Variable> variableByRecipeId) {
            for (final ResourceKey resource : constrainedResources) {
                throwIfCancelled();
                final Expression expression = model.newExpression("constraint:" + resource);
                final long lowerBound = target.getAmount(resource) - startingResources.getAmount(resource);
                expression.lower(lowerBound);
                for (final LpPatternRecipe recipe : recipes) {
                    throwIfCancelled();
                    final long coefficient = recipe.coefficient(resource);
                    if (coefficient != 0) {
                        expression.set(variableByRecipeId.get(recipe.uniqueId()), coefficient);
                    }
                }
            }
        }

        private void addRecipeLocks(final ExpressionsBasedModel model,
                                    final Map<UUID, Variable> variableByRecipeId,
                                    final Map<UUID, Long> lockedRecipeValues) {
            for (final Map.Entry<UUID, Long> lock : lockedRecipeValues.entrySet()) {
                throwIfCancelled();
                final Expression lockExpression = model.newExpression("lock:" + lock.getKey());
                lockExpression.level(lock.getValue());
                lockExpression.set(variableByRecipeId.get(lock.getKey()), 1);
            }
        }

        private Map<UUID, Long> extractUsedRecipeValues(final Map<UUID, Variable> variableByRecipeId) {
            final Map<UUID, Long> recipeValues = new LinkedHashMap<>();
            for (final LpPatternRecipe recipe : recipes) {
                throwIfCancelled();
                final Variable variable = variableByRecipeId.get(recipe.uniqueId());
                final long value = variable.getValue() == null ? 0L : Math.round(variable.getValue().doubleValue());
                if (value > 0) {
                    recipeValues.put(recipe.uniqueId(), value);
                }
            }
            return recipeValues;
        }

        private LpResourceSet computeFinalInventoryValues(final Map<UUID, Long> recipeValues) {
            final LpResourceSet finalInventoryValues = LpResourceSet.empty();
            for (final ResourceKey resource : relevantResources) {
                throwIfCancelled();
                long amount = startingResources.getAmount(resource);
                for (final LpPatternRecipe recipe : recipes) {
                    throwIfCancelled();
                    final long usage = recipeValues.getOrDefault(recipe.uniqueId(), 0L);
                    if (usage == 0) {
                        continue;
                    }
                    amount += recipe.coefficient(resource) * usage;
                }
                finalInventoryValues.setAmount(resource, amount);
            }
            return finalInventoryValues;
        }
    }

    private record FlowSearchResult(Map<UUID, Long> recipeValues, LpResourceSet finalInventoryValues) {
    }
}
