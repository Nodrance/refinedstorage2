package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

public final class LinearSolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinearSolver.class);
    private static final long WAIT_SLICE_MILLIS = 250L;

    private final List<SanitizedRecipe> recipes;
    private final List<SanitizedRecipe> reversePriorityRecipes;
    private final Set<MultiResourceKey> relevantResources;
    private final ResourcePool startingResources;
    private final ResourcePool target;
    private final Set<MultiResourceKey> unconstrainedResources;
    private final Set<UUID> disabledRecipeIds;
    private final Options options;
    private final CancellationToken cancellationToken;

    public LinearSolver(
        final List<SanitizedRecipe> recipes,
        final Set<MultiResourceKey> relevantResources,
        final ResourcePool startingResources,
        final ResourcePool target,
        final Set<MultiResourceKey> unconstrainedResources,
        final Set<UUID> disabledRecipeIds,
        final Options options,
        final CancellationToken cancellationToken
    ) {
        this.recipes = List.copyOf(recipes);
        this.reversePriorityRecipes = recipes.stream()
            .sorted(Comparator.comparingLong(SanitizedRecipe::priority).thenComparing(SanitizedRecipe::recipeId))
            .toList();
        this.relevantResources = Set.copyOf(relevantResources);
        this.startingResources = startingResources.copy();
        this.target = target.copy();
        this.unconstrainedResources = Set.copyOf(unconstrainedResources);
        this.disabledRecipeIds = Set.copyOf(disabledRecipeIds);
        this.options = Objects.requireNonNull(options, "options cannot be null");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
    }

    public Result lexicographicMinimum() {
        // Finds the solution for the target that minimizes each recipe
        // Starts with lowest priority recipes, "shifting" their uses to higher priority ones
        // Until we end up minimizing all recipes
        throwIfCancelled();
        final Result feasibilityResult = solveWithObjective(null, null, false, Map.of());
        if (feasibilityResult == null) {
            LOGGER.debug("[LP] lexicographicMinimum: no feasible solution for target {}", target);
            return null;
        }

        final Map<UUID, Long> lockedRecipeValues = new LinkedHashMap<>();
        for (final SanitizedRecipe recipe : reversePriorityRecipes) {
            throwIfCancelled();
            final Result result = solveWithObjective(null, recipe.recipeId(), false, lockedRecipeValues);
            Objects.requireNonNull(result, "Expected lexicographic lock step to remain feasible");
            lockedRecipeValues.put(recipe.recipeId(), result.recipeValues().getOrDefault(recipe.recipeId(), 0L));
        }

        final Result result = solveWithObjective(null, null, false, lockedRecipeValues);
        if (result == null) {
            LOGGER.debug(
                "[LP] lexicographicMinimum: became infeasible after locking {} recipes for target {}",
                lockedRecipeValues.size(),
                target
            );
            return null;
        }
        LOGGER.debug(
            "[LP] lexicographicMinimum: solved with activeRecipes={}, totalRecipeApplications={}, "
                + "nonZeroFinalInventoryResources= {}",
            result.recipeValues().size(),
            sumRecipeApplications(result.recipeValues()),
            countNonZeroResources(result.finalInventoryValues())
        );
        return result;
    }

    public boolean hasFeasibleSolution() {
        // Whether the target is achievable at all, without any optimization objective. 
        // This is a quick check used in some places to avoid doing more expensive solves when the target is outright impossible.
        throwIfCancelled();
        final boolean feasible = solveWithObjective(null, null, false, Map.of()) != null;
        LOGGER.debug("[LP] hasFeasibleSolution: target={} feasible={}", target, feasible);
        return feasible;
    }

    public Result maximize(final MultiResourceKey objectiveResource) {
        // Finds the solution that maximizes the given objective resource
        // Used in calculating max craftable amounts
        throwIfCancelled();
        final Result result = solveWithObjective(
            Objects.requireNonNull(objectiveResource, "objectiveResource cannot be null"),
            null,
            true,
            Map.of()
        );
        LOGGER.debug(
            "[LP] maximize: objectiveResource={} feasible={} activeRecipes={} totalRecipeApplications={}",
            objectiveResource,
            result != null,
            result == null ? 0 : result.recipeValues().size(),
            result == null ? 0 : sumRecipeApplications(result.recipeValues())
        );
        return result;
    }

    public Result minimizeTotalDeficitWithFloor(
        final Set<MultiResourceKey> deficitResources,
        final ResourcePool minimumFinalInventory
    ) {
        // Minimizes the total number of items you'd need to add in order to make the recipe craftable
        // Also ensures that no individual resource ends up worse than it started before
        throwIfCancelled();
        final Set<MultiResourceKey> sanitizedDeficitResources = Set.copyOf(
            Objects.requireNonNull(deficitResources, "deficitResources cannot be null")
        );
        final ResourcePool sanitizedMinimumFinalInventory = Objects.requireNonNull(
            minimumFinalInventory,
            "minimumFinalInventory cannot be null"
        ).copy();
        final long deficitBefore = sumDeficitAmounts(sanitizedMinimumFinalInventory, sanitizedDeficitResources);
        final Result result = solveForDeficitObjective(sanitizedDeficitResources, sanitizedMinimumFinalInventory);
        final long deficitAfter = 
        result == null ? deficitBefore
            : sumDeficitAmounts(result.finalInventoryValues(), sanitizedDeficitResources);
        final boolean deficitDecreased = result != null && deficitAfter < deficitBefore;
        LOGGER.debug(
            "[LP] minimizeTotalDeficitWithFloor: deficitResourceCount={} feasible={} "
                + "totalDeficitBefore={} totalDeficitAfter={} deficitDecreased={}",
            sanitizedDeficitResources.size(),
            result != null,
            deficitBefore,
            deficitAfter,
            deficitDecreased
        );
        return result;
    }

    private Result solveWithObjective(
        final MultiResourceKey objectiveResource,
        final UUID objectiveRecipeId,
        final boolean maximize,
        final Map<UUID, Long> lockedRecipeValues
    ) {
        // Solves a linear programming problem, minimizing or maximizing the given objective
        throwIfCancelled();

        final FutureTask<Result> solveTask = new FutureTask<>(
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

    private Result solveForDeficitObjective(
        final Set<MultiResourceKey> deficitResources,
        final ResourcePool minimumFinalInventory
    ) {
        throwIfCancelled();

        final FutureTask<Result> solveTask = new FutureTask<>(
            () -> solveForDeficitObjectiveInternal(deficitResources, minimumFinalInventory)
        );
        final Thread solveThread = new Thread(solveTask, "lp-ojalgo-deficit-solve");
        solveThread.setDaemon(true);
        solveThread.start();

        try {
            return awaitNonCooperativeTask(
                solveTask,
                solveThread,
                "solveForDeficitObjective",
                false,
                null,
                null,
                false,
                Map.of()
            );
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            cancellationToken.cancel();
            solveThread.interrupt();
            throw new CancellationException("Interrupted in solveForDeficitObjective");
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("Failed in solveForDeficitObjective", cause);
        }
    }

    private Result solveWithObjectiveInternal(
        final MultiResourceKey objectiveResource,
        final UUID objectiveRecipeId,
        final boolean maximize,
        final Map<UUID, Long> lockedRecipeValues
    ) {
        throwIfCancelled();

        final ExpressionsBasedModel model = createModelWithDiagnostics();
        final Map<UUID, Variable> variableByRecipeId = createRecipeVariables(model);
        configureObjective(model, variableByRecipeId, objectiveResource, objectiveRecipeId);
        addResourceConstraints(model, variableByRecipeId);
        addRecipeLocks(model, variableByRecipeId, lockedRecipeValues);
        throwIfCancelled();

        final Optimisation.Result result = maximize ? model.maximise() : model.minimise();
        if (!result.getState().isFeasible()) {
            return null;
        }

        final Map<UUID, Long> recipeValues = extractUsedRecipeValues(variableByRecipeId);
        final ResourcePool finalInventoryValues = computeFinalInventoryValues(recipeValues);
        return new Result(Map.copyOf(recipeValues), finalInventoryValues.copy());
    }

    private Result solveForDeficitObjectiveInternal(
        final Set<MultiResourceKey> deficitResources,
        final ResourcePool minimumFinalInventory
    ) {
        throwIfCancelled();

        final ExpressionsBasedModel model = createModelWithDiagnostics();
        final Map<UUID, Variable> variableByRecipeId = createRecipeVariables(model);
        addResourceConstraints(model, variableByRecipeId);
        addFinalInventoryFloorConstraints(model, variableByRecipeId, minimumFinalInventory, deficitResources);
        configureDeficitObjective(model, variableByRecipeId, deficitResources);
        throwIfCancelled();

        final Optimisation.Result result = model.minimise();
        if (!result.getState().isFeasible()) {
            return null;
        }

        final Map<UUID, Long> recipeValues = extractUsedRecipeValues(variableByRecipeId);
        final ResourcePool finalInventoryValues = computeFinalInventoryValues(recipeValues);
        return new Result(Map.copyOf(recipeValues), finalInventoryValues.copy());
    }

    private ExpressionsBasedModel createModelWithDiagnostics() {
        throwIfCancelled();

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

    private <T> T awaitNonCooperativeTask(
        final FutureTask<T> task,
        final Thread workerThread,
        final String description,
        final boolean logObjectiveDetails,
        final MultiResourceKey objectiveResource,
        final UUID objectiveRecipeId,
        final boolean maximize,
        final Map<UUID, Long> lockedRecipeValues
    ) throws InterruptedException, ExecutionException {
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

    private void timeoutNonCooperativeTask(
        final Thread workerThread,
        final String description,
        final boolean logObjectiveDetails,
        final MultiResourceKey objectiveResource,
        final UUID objectiveRecipeId,
        final boolean maximize,
        final Map<UUID, Long> lockedRecipeValues
    ) {
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
        for (final SanitizedRecipe recipe : recipes) {
            throwIfCancelled();
            final boolean disabled = disabledRecipeIds.contains(recipe.recipeId());
            final Variable variable = model.addVariable(recipe.recipeId().toString())
                .integer(true)
                .lower(0)
                .upper(disabled ? 0 : options.recipeUpperBound());
            variableByRecipeId.put(recipe.recipeId(), variable);
        }
        return variableByRecipeId;
    }

    private void configureObjective(
        final ExpressionsBasedModel model,
        final Map<UUID, Variable> variableByRecipeId,
        final MultiResourceKey objectiveResource,
        final UUID objectiveRecipeId
    ) {
        throwIfCancelled();
        if (objectiveResource == null && objectiveRecipeId == null) {
            return;
        }

        final Expression objective = model.newExpression("objective").weight(1);
        for (final SanitizedRecipe recipe : recipes) {
            throwIfCancelled();
            final long coefficient = objectiveCoefficient(recipe, objectiveResource, objectiveRecipeId);
            if (coefficient != 0) {
                objective.set(variableByRecipeId.get(recipe.recipeId()), coefficient);
            }
        }
    }

    private long objectiveCoefficient(
        final SanitizedRecipe recipe,
        final MultiResourceKey objectiveResource,
        final UUID objectiveRecipeId
    ) {
        if (objectiveRecipeId != null) {
            return recipe.recipeId().equals(objectiveRecipeId) ? 1 : 0;
        }
        return recipeCoefficient(recipe, objectiveResource);
    }

    private void addResourceConstraints(
        final ExpressionsBasedModel model,
        final Map<UUID, Variable> variableByRecipeId
    ) {
        for (final MultiResourceKey resource : relevantResources) {
            throwIfCancelled();
            if (unconstrainedResources.contains(resource)) {
                continue;
            }
            final Expression expression = model.newExpression("constraint:" + resource);
            final long lowerBound = target.getAmount(resource) - startingResources.getAmount(resource);
            expression.lower(lowerBound);
            for (final SanitizedRecipe recipe : recipes) {
                throwIfCancelled();
                final long coefficient = recipeCoefficient(recipe, resource);
                if (coefficient != 0) {
                    expression.set(variableByRecipeId.get(recipe.recipeId()), coefficient);
                }
            }
        }
    }

    private void addRecipeLocks(
        final ExpressionsBasedModel model,
        final Map<UUID, Variable> variableByRecipeId,
        final Map<UUID, Long> lockedRecipeValues
    ) {
        for (final Map.Entry<UUID, Long> lock : lockedRecipeValues.entrySet()) {
            throwIfCancelled();
            final Expression lockExpression = model.newExpression("lock:" + lock.getKey());
            lockExpression.level(lock.getValue());
            lockExpression.set(variableByRecipeId.get(lock.getKey()), 1);
        }
    }

    private void addFinalInventoryFloorConstraints(
        final ExpressionsBasedModel model,
        final Map<UUID, Variable> variableByRecipeId,
        final ResourcePool minimumFinalInventory,
        final Set<MultiResourceKey> resources
    ) {
        for (final MultiResourceKey resource : resources) {
            throwIfCancelled();
            final Expression floorExpression = model.newExpression("floor:" + resource);
            final long lowerBound = minimumFinalInventory.getAmount(resource) - startingResources.getAmount(resource);
            floorExpression.lower(lowerBound);
            for (final SanitizedRecipe recipe : recipes) {
                throwIfCancelled();
                final long coefficient = recipeCoefficient(recipe, resource);
                if (coefficient != 0) {
                    floorExpression.set(variableByRecipeId.get(recipe.recipeId()), coefficient);
                }
            }
        }
    }

    private void configureDeficitObjective(
        final ExpressionsBasedModel model,
        final Map<UUID, Variable> variableByRecipeId,
        final Set<MultiResourceKey> deficitResources
    ) {
        final Expression objective = model.newExpression("deficit-objective").weight(1);
        for (final MultiResourceKey resource : deficitResources) {
            throwIfCancelled();
            final Variable deficitVariable = model.addVariable("deficit:" + resource)
                .integer(true)
                .lower(0);
            objective.set(deficitVariable, 1);

            final Expression deficitDefinition = model.newExpression("deficit-constraint:" + resource);
            deficitDefinition.lower(target.getAmount(resource) - startingResources.getAmount(resource));
            deficitDefinition.set(deficitVariable, 1);
            for (final SanitizedRecipe recipe : recipes) {
                throwIfCancelled();
                final long coefficient = recipeCoefficient(recipe, resource);
                if (coefficient != 0) {
                    deficitDefinition.set(variableByRecipeId.get(recipe.recipeId()), coefficient);
                }
            }
        }
    }

    private Map<UUID, Long> extractUsedRecipeValues(final Map<UUID, Variable> variableByRecipeId) {
        final Map<UUID, Long> recipeValues = new LinkedHashMap<>();
        for (final SanitizedRecipe recipe : recipes) {
            throwIfCancelled();
            final Variable variable = variableByRecipeId.get(recipe.recipeId());
            final long value = variable.getValue() == null ? 0L : Math.round(variable.getValue().doubleValue());
            if (value > 0) {
                recipeValues.put(recipe.recipeId(), value);
            }
        }
        return recipeValues;
    }

    private ResourcePool computeFinalInventoryValues(final Map<UUID, Long> recipeValues) {
        final ResourcePool finalInventoryValues = ResourcePool.empty();
        for (final MultiResourceKey resource : relevantResources) {
            throwIfCancelled();
            long amount = startingResources.getAmount(resource);
            for (final SanitizedRecipe recipe : recipes) {
                throwIfCancelled();
                final long usage = recipeValues.getOrDefault(recipe.recipeId(), 0L);
                if (usage == 0) {
                    continue;
                }
                amount += recipeCoefficient(recipe, resource) * usage;
            }
            // LOGGER.debug(
            //     "[LP] computeFinalInventoryValues: resource={} startingAmount={} netRecipeChange={} finalAmount={}",
            //     resource,
            //     startingResources.getAmount(resource),
            //     amount - startingResources.getAmount(resource),
            //     amount
            // );
            finalInventoryValues.setAmount(resource, amount);
        }
        return finalInventoryValues;
    }

    private static long recipeCoefficient(final SanitizedRecipe recipe, final MultiResourceKey resource) {
        return recipe.output().getAmount(resource) - recipe.input().getAmount(resource);
    }

    private static long sumRecipeApplications(final Map<UUID, Long> recipeValues) {
        long total = 0L;
        for (final long value : recipeValues.values()) {
            total += value;
        }
        return total;
    }

    private static int countNonZeroResources(final ResourcePool finalInventoryValues) {
        int count = 0;
        for (final Map.Entry<MultiResourceKey, Long> entry : finalInventoryValues) {
            if (entry.getValue() != 0L) {
                count++;
            }
        }
        return count;
    }

    private long sumDeficitAmounts(
        final ResourcePool finalInventoryValues,
        final Set<MultiResourceKey> deficitResources
    ) {
        long total = 0L;
        for (final MultiResourceKey resource : deficitResources) {
            final long deficit = Math.max(0L, target.getAmount(resource) - finalInventoryValues.getAmount(resource));
            total += deficit;
        }
        return total;
    }

    public record Result(Map<UUID, Long> recipeValues, ResourcePool finalInventoryValues) {
    }

    public record Options(int recipeUpperBound, int maxCycleEliminationBranches) {
        public static final int DEFAULT_RECIPE_UPPER_BOUND = 1_000_000_000;
        public static final int DEFAULT_MAX_CYCLE_ELIMINATION_BRANCHES = 512;

        public Options {
            if (recipeUpperBound <= 0) {
                throw new IllegalArgumentException("recipeUpperBound must be larger than zero");
            }
            if (maxCycleEliminationBranches <= 0) {
                throw new IllegalArgumentException("maxCycleEliminationBranches must be larger than zero");
            }
        }

        public static Options defaults() {
            return new Options(DEFAULT_RECIPE_UPPER_BOUND, DEFAULT_MAX_CYCLE_ELIMINATION_BRANCHES);
        }
    }
}
