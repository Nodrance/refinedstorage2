package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatusBuilder;
import com.refinedmods.refinedstorage.api.autocrafting.task.AbstractTaskPattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.PatternStepResult;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskSnapshot;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceListImpl;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CyclicTaskImpl extends TaskImpl {
    private static final Logger LOGGER = LoggerFactory.getLogger(CyclicTaskImpl.class);

    private final long startTime;
    private final MutableResourceList initialRequirements = MutableResourceListImpl.create();
    private final MutableResourceList internalStorage = MutableResourceListImpl.create();
    private final Map<Pattern, AbstractTaskPattern> patterns;
    private final List<AbstractTaskPattern> completedPatterns = new ArrayList<>();
    private final Pattern rootPattern;

    private final List<Pattern> plannerPatternOrder;
    private final Map<Pattern, Integer> plannerOverrideRemainingSteps;
    private final Map<Pattern, Set<ResourceKey>> consumedResourcesByPattern;
    private final Map<Pattern, Integer> cycleSafeStepBudgets = new LinkedHashMap<>();

    private TaskState state = TaskState.READY;
    private boolean cancelled;
    private boolean queueDirty = true;

    CyclicTaskImpl(final ResourceKey resource,
                   final long amount,
                   final Actor actor,
                   final boolean notify,
                   final DesanitizedRecipeApplicationPath path,
                   final Map<UUID, Pattern> patternsById,
                   final Pattern rootPattern,
                   final Predicate<Pattern> hasProvider) {
        super(TaskDispatcher.createCyclicTaskPlan(resource, amount, rootPattern), actor, notify);
        this.startTime = System.currentTimeMillis();
        this.rootPattern = rootPattern;

        final CyclicMergedPlan mergedPlan = toMergedPlan(resource, amount, path.steps(), patternsById, rootPattern);
        mergedPlan.initialRequirements().forEach(initialRequirements::add);
        this.plannerOverrideRemainingSteps = new HashMap<>(mergedPlan.plannerOverrideInitialSteps());
        this.consumedResourcesByPattern = mergedPlan.consumedResourcesByPattern();

        final LinkedHashSet<Pattern> order = new LinkedHashSet<>();
        for (final DesanitizedRecipeApplicationStep step : path.steps()) {
            final Pattern pattern = TaskDispatcher.requirePattern(step.recipe(), patternsById);
            order.add(pattern);
        }
        this.plannerPatternOrder = List.copyOf(order);

        final Map<Pattern, AbstractTaskPattern> mergedPatterns = mergedPlan.patternPlans().entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> TaskImpl.createTaskPatternInternal(e.getKey(), e.getValue()),
            (a, b) -> a,
            LinkedHashMap::new
        ));

        final LinkedHashMap<Pattern, AbstractTaskPattern> orderedPatterns = new LinkedHashMap<>();
        final AbstractTaskPattern rootTaskPattern = mergedPatterns.get(rootPattern);
        if (rootTaskPattern != null) {
            orderedPatterns.put(rootPattern, rootTaskPattern);
        }
        for (final Pattern pattern : plannerPatternOrder) {
            final AbstractTaskPattern taskPattern = mergedPatterns.get(pattern);
            if (taskPattern != null) {
                orderedPatterns.put(pattern, taskPattern);
            }
        }
        mergedPatterns.forEach(orderedPatterns::putIfAbsent);
        this.patterns = orderedPatterns;
    }

    @Override
    public TaskState getState() {
        return state;
    }

    @Override
    public boolean shouldNotify() {
        return super.shouldNotify() && !cancelled;
    }

    @Override
    protected void updateState(final TaskState newState) {
        LOGGER.debug("Task {} state changed from {} to {}", getId().id(), state, newState);
        this.state = newState;
    }

    @Override
    public boolean step(final RootStorage rootStorage,
                        final ExternalPatternSinkProvider sinkProvider,
                        final StepBehavior stepBehavior,
                        final TaskListener listener) {
        return switch (state) {
            case READY -> startTask(rootStorage);
            case EXTRACTING_INITIAL_RESOURCES -> extractInitialResourcesAndTryStartRunningTask(rootStorage);
            case RUNNING -> stepPatterns(rootStorage, sinkProvider, stepBehavior, listener);
            case RETURNING_INTERNAL_STORAGE -> returnInternalStorageAndTryCompleteTask(rootStorage);
            case COMPLETED -> false;
        };
    }

    @Override
    public void cancel() {
        state = TaskState.RETURNING_INTERNAL_STORAGE;
        cancelled = true;
    }

    @Override
    public TaskStatus getStatus() {
        final TaskStatusBuilder builder = new TaskStatusBuilder(getId(), state, getResource(), getAmount(), startTime);
        initialRequirements.getAll().forEach(
            requiredResource -> builder.extracting(requiredResource, initialRequirements.get(requiredResource))
        );

        double totalWeightedCompleted = 0;
        double totalWeight = 0;
        for (final AbstractTaskPattern pattern : getStatusOrderedPatterns()) {
            pattern.appendStatus(builder);
            final long weight = getPatternWeight(pattern);
            totalWeightedCompleted += getPatternPercentageCompleted(pattern) * weight;
            totalWeight += weight;
        }
        for (final AbstractTaskPattern pattern : completedPatterns) {
            final long weight = getPatternWeight(pattern);
            totalWeightedCompleted += weight;
            totalWeight += weight;
        }

        internalStorage.getAll().forEach(
            internalResource -> builder.stored(internalResource, internalStorage.get(internalResource))
        );
        return builder.build(totalWeight == 0 ? 0 : totalWeightedCompleted / totalWeight);
    }

    private List<AbstractTaskPattern> getStatusOrderedPatterns() {
        final List<AbstractTaskPattern> ordered = new ArrayList<>();
        final AbstractTaskPattern root = patterns.get(rootPattern);
        if (root != null) {
            ordered.add(root);
        }
        for (final var entry : patterns.entrySet()) {
            if (entry.getKey().equals(rootPattern)) {
                continue;
            }
            ordered.add(entry.getValue());
        }
        return ordered;
    }

    @Override
    public TaskSnapshot createSnapshot() {
        return new TaskSnapshot(
            getId(),
            getResource(),
            getAmount(),
            getActor(),
            shouldNotify(),
            startTime,
            patterns.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> createPatternSnapshot(e.getValue()),
                (a, b) -> a,
                LinkedHashMap::new
            )),
            completedPatterns.stream()
                .filter(pattern -> pattern.getClass().getSimpleName().equals("InternalTaskPattern"))
                .map(CyclicTaskImpl::createPatternSnapshot)
                .toList(),
            initialRequirements.copy(),
            internalStorage.copy(),
            state,
            cancelled
        );
    }

    private boolean startTask(final RootStorage rootStorage) {
        updateState(TaskState.EXTRACTING_INITIAL_RESOURCES);
        return extractInitialResourcesAndTryStartRunningTask(rootStorage);
    }

    private boolean extractInitialResourcesAndTryStartRunningTask(final RootStorage rootStorage) {
        boolean extractedAll = true;
        boolean extractedAny = false;
        final Set<ResourceKey> initialRequirementResources = new HashSet<>(initialRequirements.getAll());
        for (final ResourceKey initialRequirementResource : initialRequirementResources) {
            final long needed = initialRequirements.get(initialRequirementResource);
            final long extracted = rootStorage.extract(initialRequirementResource, needed, Action.EXECUTE, Actor.EMPTY);
            if (extracted > 0) {
                extractedAny = true;
            }
            LOGGER.debug("Extracted {}x {} from storage", extracted, initialRequirementResource);
            if (extracted != needed) {
                extractedAll = false;
            }
            if (extracted > 0) {
                initialRequirements.remove(initialRequirementResource, extracted);
                internalStorage.add(initialRequirementResource, extracted);
            }
        }
        if (extractedAll) {
            updateState(TaskState.RUNNING);
            queueDirty = true;
        }
        return extractedAny;
    }

    private boolean stepPatterns(final RootStorage rootStorage,
                                 final ExternalPatternSinkProvider sinkProvider,
                                 final StepBehavior stepBehavior,
                                 final TaskListener listener) {
        if (queueDirty || cycleSafeStepBudgets.isEmpty()) {
            recalculateStepSafeQueue();
        }

        final var it = patterns.entrySet().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            final var pattern = it.next();
            final int budget = cycleSafeStepBudgets.getOrDefault(pattern.getKey(), 0);
            if (budget <= 0) {
                continue;
            }

            final StepExecutionResult result = stepPattern(
                rootStorage,
                sinkProvider,
                stepBehavior,
                listener,
                pattern
            );
            consumeStepBudget(pattern.getKey(), result.executedSteps());

            if (result.result() == PatternStepResult.COMPLETED) {
                LOGGER.debug("{} completed", pattern.getKey());
                completedPatterns.add(pattern.getValue());
                it.remove();
                cycleSafeStepBudgets.remove(pattern.getKey());
                plannerOverrideRemainingSteps.remove(pattern.getKey());
                queueDirty = true;
            }
            changed |= result.result() != PatternStepResult.IDLE;
        }

        if (patterns.isEmpty()) {
            if (internalStorage.isEmpty()) {
                updateState(TaskState.COMPLETED);
            } else {
                updateState(TaskState.RETURNING_INTERNAL_STORAGE);
            }
        }
        return changed;
    }

    private StepExecutionResult stepPattern(final RootStorage rootStorage,
                                            final ExternalPatternSinkProvider sinkProvider,
                                            final StepBehavior stepBehavior,
                                            final TaskListener listener,
                                            final Map.Entry<Pattern, AbstractTaskPattern> pattern) {
        PatternStepResult result = PatternStepResult.IDLE;
        if (!stepBehavior.canStep(pattern.getKey())) {
            return new StepExecutionResult(result, 0);
        }

        int executedSteps = 0;
        final int steps = stepBehavior.getSteps(pattern.getKey());
        int maxSteps = steps;
        final int budget = cycleSafeStepBudgets.getOrDefault(pattern.getKey(), Integer.MAX_VALUE);
        if (budget != Integer.MAX_VALUE) {
            maxSteps = Math.min(maxSteps, budget);
        }

        for (int i = 0; i < maxSteps; ++i) {
            executedSteps++;
            final PatternStepResult stepResult = pattern.getValue().step(
                internalStorage,
                rootStorage,
                sinkProvider,
                listener
            );
            if (stepResult == PatternStepResult.COMPLETED) {
                return new StepExecutionResult(stepResult, executedSteps);
            } else if (stepResult != PatternStepResult.IDLE) {
                result = PatternStepResult.RUNNING;
            }
        }

        return new StepExecutionResult(result, executedSteps);
    }

    private boolean returnInternalStorageAndTryCompleteTask(final RootStorage rootStorage) {
        boolean returnedAll = true;
        boolean returnedAny = false;
        final Set<ResourceKey> internalResources = new HashSet<>(internalStorage.getAll());
        for (final ResourceKey internalResource : internalResources) {
            final long internalAmount = internalStorage.get(internalResource);
            final long inserted = rootStorage.insert(internalResource, internalAmount, Action.EXECUTE, Actor.EMPTY);
            if (inserted > 0) {
                returnedAny = true;
            }
            LOGGER.debug("Returned {}x {} into storage", inserted, internalResource);
            if (inserted != internalAmount) {
                returnedAll = false;
            }
            if (inserted > 0) {
                internalStorage.remove(internalResource, inserted);
            }
        }
        if (returnedAll) {
            updateState(TaskState.COMPLETED);
        }
        return returnedAny;
    }

    @Override
    public long beforeInsert(final ResourceKey insertedResource, final long insertedAmount) {
        if (cancelled) {
            return 0;
        }
        long intercepted = 0;
        for (final AbstractTaskPattern pattern : patterns.values()) {
            final long available = insertedAmount - intercepted;
            intercepted += pattern.beforeInsert(insertedResource, available);
            if (intercepted == insertedAmount) {
                internalStorage.add(insertedResource, intercepted);
                return intercepted;
            }
        }
        if (intercepted > 0) {
            internalStorage.add(insertedResource, intercepted);
        }
        return intercepted;
    }

    @Override
    public long afterInsert(final ResourceKey insertedResource, final long insertedAmount) {
        long reserved = 0;
        for (final AbstractTaskPattern pattern : patterns.values()) {
            final long available = insertedAmount - reserved;
            reserved += pattern.afterInsert(insertedResource, available);
            if (reserved == insertedAmount) {
                return reserved;
            }
        }
        return reserved;
    }

    @Override
    public void changed(final MutableResourceList.OperationResult change) {
        queueDirty = true;
    }

    private double progress() {
        final int total = patterns.size() + completedPatterns.size();
        if (total == 0) {
            return 1D;
        }
        return completedPatterns.size() / (double) total;
    }

    private void recalculateStepSafeQueue() {
        cycleSafeStepBudgets.clear();
        cycleSafeStepBudgets.putAll(CycleSafeBudgetPlanner.computeStepBudgets(
            patterns.keySet(),
            plannerPatternOrder,
            plannerOverrideRemainingSteps,
            consumedResourcesByPattern
        ));
        queueDirty = false;
    }

    private void consumeStepBudget(final Pattern pattern,
                                   final int executedSteps) {
        if (executedSteps <= 0) {
            return;
        }
        final int budget = cycleSafeStepBudgets.getOrDefault(pattern, 0);
        if (budget == Integer.MAX_VALUE) {
            return;
        }

        final int remaining = Math.max(0, budget - executedSteps);
        if (remaining == 0) {
            cycleSafeStepBudgets.remove(pattern);
            plannerOverrideRemainingSteps.put(pattern, 0);
            queueDirty = true;
        } else {
            cycleSafeStepBudgets.put(pattern, remaining);
            plannerOverrideRemainingSteps.put(pattern, remaining);
        }
    }

    private static CyclicMergedPlan toMergedPlan(final ResourceKey requestedResource,
                                                  final long requestedAmount,
                                                  final List<DesanitizedRecipeApplicationStep> steps,
                                                  final Map<UUID, Pattern> patternsById,
                                                  final Pattern rootPattern) {
        final Map<Pattern, MutablePatternPlan> mutablePlans = new LinkedHashMap<>();

        for (final DesanitizedRecipeApplicationStep step : steps) {
            final Pattern stepPattern = TaskDispatcher.requirePattern(step.recipe(), patternsById);
            final boolean root = stepPattern.equals(rootPattern);
            final TaskPlan stepPlan = TaskDispatcher.translateLpStepToTaskPlan(
                requestedResource,
                -1,
                step,
                patternsById,
                root
            );
            final var entry = stepPlan.patterns().entrySet().iterator().next();

            final Pattern pattern = entry.getKey();
            final TaskPlan.PatternPlan patternPlan = entry.getValue();
            final MutablePatternPlan mutable = mutablePlans.computeIfAbsent(pattern, ignored ->
                new MutablePatternPlan(false, 0, new LinkedHashMap<>()));

            mutable.root = mutable.root || patternPlan.root();
            mutable.iterations += patternPlan.iterations();

            for (final var ingredientEntry : patternPlan.ingredients().entrySet()) {
                final int ingredientIndex = ingredientEntry.getKey();
                final Map<ResourceKey, Long> targetByResource = mutable.ingredients
                    .computeIfAbsent(ingredientIndex, ignored -> new LinkedHashMap<>());
                for (final var possibility : ingredientEntry.getValue().entrySet()) {
                    targetByResource.merge(possibility.getKey(), possibility.getValue(), Long::sum);
                }
            }
        }

        final Map<Pattern, TaskPlan.PatternPlan> patterns = new LinkedHashMap<>();
        final Map<Pattern, Integer> plannerOverrideInitialSteps = new LinkedHashMap<>();
        final Map<Pattern, Set<ResourceKey>> consumedResourcesByPattern = new LinkedHashMap<>();
        final Map<ResourceKey, Long> totalInputs = new LinkedHashMap<>();
        final Map<ResourceKey, Long> totalProduced = new LinkedHashMap<>();

        for (final var entry : mutablePlans.entrySet()) {
            final Pattern pattern = entry.getKey();
            final MutablePatternPlan mutable = entry.getValue();
            final long totalIterations = mutable.iterations;
            final Map<Integer, Map<ResourceKey, Long>> redistributedIngredients = redistributeIngredientTotals(
                pattern,
                totalIterations,
                mutable.ingredients
            );
            final Map<Integer, Map<ResourceKey, Long>> immutableIngredients = new LinkedHashMap<>();

            for (final var ingredientEntry : redistributedIngredients.entrySet()) {
                final int ingredientIndex = ingredientEntry.getKey();
                final Map<ResourceKey, Long> orderedByInputPreference = orderByIngredientInputPreference(
                    pattern,
                    ingredientIndex,
                    ingredientEntry.getValue()
                );
                immutableIngredients.put(ingredientIndex, Map.copyOf(orderedByInputPreference));
                orderedByInputPreference.forEach((resource, amount) -> totalInputs.merge(resource, amount, Long::sum));
            }

            final Set<ResourceKey> consumedResources = new LinkedHashSet<>();
            immutableIngredients.values().forEach(ingredientResources -> consumedResources.addAll(ingredientResources.keySet()));
            consumedResourcesByPattern.put(pattern, Set.copyOf(consumedResources));

            plannerOverrideInitialSteps.put(pattern, (int) Math.min(Integer.MAX_VALUE, Math.max(0L, totalIterations)));
            pattern.layout().outputs().forEach(output ->
                totalProduced.merge(output.resource(), output.amount() * totalIterations, Long::sum));
            pattern.layout().byproducts().forEach(byproduct ->
                totalProduced.merge(byproduct.resource(), byproduct.amount() * totalIterations, Long::sum));

            patterns.put(pattern, new TaskPlan.PatternPlan(mutable.root, totalIterations, Map.copyOf(immutableIngredients)));
        }

        final List<ResourceAmount> mergedInitialRequirements = new ArrayList<>();
        totalInputs.forEach((resource, needed) -> {
            final long produced = totalProduced.getOrDefault(resource, 0L);
            final long requiredFromStorage = Math.max(0L, needed - produced);
            if (requiredFromStorage > 0L) {
                mergedInitialRequirements.add(new ResourceAmount(resource, requiredFromStorage));
            }
        });

        return new CyclicMergedPlan(
            Map.copyOf(patterns),
            List.copyOf(mergedInitialRequirements),
            Map.copyOf(consumedResourcesByPattern),
            Map.copyOf(plannerOverrideInitialSteps)
        );
    }

    private static Map<ResourceKey, Long> orderByIngredientInputPreference(final Pattern pattern,
                                                                            final int ingredientIndex,
                                                                            final Map<ResourceKey, Long> amounts) {
        final LinkedHashMap<ResourceKey, Long> ordered = new LinkedHashMap<>();
        final List<ResourceKey> preferredOrder = pattern.layout().ingredients().get(ingredientIndex).inputs();

        for (final ResourceKey resource : preferredOrder) {
            final Long amount = amounts.get(resource);
            if (amount != null && amount > 0) {
                ordered.put(resource, amount);
            }
        }
        amounts.forEach((resource, amount) -> {
            if (amount > 0) {
                ordered.putIfAbsent(resource, amount);
            }
        });
        return ordered;
    }

    private static Map<Integer, Map<ResourceKey, Long>> redistributeIngredientTotals(final Pattern pattern,
                                                                                      final long totalIterations,
                                                                                      final Map<Integer, Map<ResourceKey, Long>> mergedIngredients) {
        final Map<ResourceKey, Long> remainingByResource = new LinkedHashMap<>();
        mergedIngredients.values().forEach(resources -> resources.forEach(
            (resource, amount) -> remainingByResource.merge(resource, amount, Long::sum)
        ));

        final Map<Integer, Map<ResourceKey, Long>> redistributed = new LinkedHashMap<>();
        for (int ingredientIndex = 0; ingredientIndex < pattern.layout().ingredients().size(); ingredientIndex++) {
            final long requiredTotal = pattern.layout().ingredients().get(ingredientIndex).amount() * totalIterations;
            long remainingRequired = requiredTotal;
            final LinkedHashMap<ResourceKey, Long> assigned = new LinkedHashMap<>();

            for (final ResourceKey preferred : pattern.layout().ingredients().get(ingredientIndex).inputs()) {
                final long available = remainingByResource.getOrDefault(preferred, 0L);
                if (available <= 0 || remainingRequired <= 0) {
                    continue;
                }
                final long taken = Math.min(remainingRequired, available);
                assigned.put(preferred, taken);
                remainingByResource.put(preferred, available - taken);
                remainingRequired -= taken;
            }

            if (remainingRequired > 0) {
                for (final var entry : remainingByResource.entrySet()) {
                    if (remainingRequired <= 0) {
                        break;
                    }
                    final long available = entry.getValue();
                    if (available <= 0) {
                        continue;
                    }
                    final long taken = Math.min(remainingRequired, available);
                    assigned.merge(entry.getKey(), taken, Long::sum);
                    entry.setValue(available - taken);
                    remainingRequired -= taken;
                }
            }

            redistributed.put(ingredientIndex, assigned);
        }

        return redistributed;
    }

    private static long getPatternWeight(final AbstractTaskPattern pattern) {
        return invokePatternMethod(pattern, "getWeight", Long.class);
    }

    private static double getPatternPercentageCompleted(final AbstractTaskPattern pattern) {
        return invokePatternMethod(pattern, "getPercentageCompleted", Double.class);
    }

    private static TaskSnapshot.PatternSnapshot createPatternSnapshot(final AbstractTaskPattern pattern) {
        return invokePatternMethod(pattern, "createSnapshot", TaskSnapshot.PatternSnapshot.class);
    }

    private static <T> T invokePatternMethod(final AbstractTaskPattern pattern,
                                             final String methodName,
                                             final Class<T> returnType) {
        try {
            final Method method = pattern.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return returnType.cast(method.invoke(pattern));
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke " + methodName + " on " + pattern.getClass(), e);
        }
    }

    private record CyclicMergedPlan(Map<Pattern, TaskPlan.PatternPlan> patternPlans,
                                    List<ResourceAmount> initialRequirements,
                                    Map<Pattern, Set<ResourceKey>> consumedResourcesByPattern,
                                    Map<Pattern, Integer> plannerOverrideInitialSteps) {
    }

    private record StepExecutionResult(PatternStepResult result,
                                       int executedSteps) {
    }

    private static final class MutablePatternPlan {
        private boolean root;
        private long iterations;
        private final Map<Integer, Map<ResourceKey, Long>> ingredients;

        private MutablePatternPlan(final boolean root,
                                   final long iterations,
                                   final Map<Integer, Map<ResourceKey, Long>> ingredients) {
            this.root = root;
            this.iterations = iterations;
            this.ingredients = ingredients;
        }
    }
}
