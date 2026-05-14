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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

final class CyclicTaskImpl extends TaskImpl {
    private static final Logger LOGGER = LoggerFactory.getLogger(CyclicTaskImpl.class);

    private final long startTime;
    private final MutableResourceList initialRequirements = MutableResourceListImpl.create();
    private final MutableResourceList internalStorage = MutableResourceListImpl.create();
    private final Map<Pattern, AbstractTaskPattern> activePatterns;
    private final List<AbstractTaskPattern> completedPatterns = new ArrayList<>();
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
                   final RecipeApplicationPath path,
                   final Map<UUID, Pattern> patternsById,
                   final Pattern rootPattern,
                   final Predicate<Pattern> hasProvider) {
        super(TaskDispatcher.createCyclicTaskPlan(resource, amount, rootPattern), actor, notify);
        this.startTime = System.currentTimeMillis();

        final CyclicMergedPlan mergedPlan = toMergedPlan(resource, amount, path.steps(), patternsById, rootPattern);
        mergedPlan.initialRequirements().forEach(initialRequirements::add);
        this.plannerOverrideRemainingSteps = new HashMap<>(mergedPlan.plannerOverrideInitialSteps());
        this.consumedResourcesByPattern = mergedPlan.consumedResourcesByPattern();
        this.activePatterns = mergedPlan.patternPlans().entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> TaskImpl.createTaskPatternInternal(e.getKey(), e.getValue()),
            (a, b) -> a,
            LinkedHashMap::new
        ));

        final LinkedHashSet<Pattern> order = new LinkedHashSet<>();
        for (final RecipeApplicationStep step : path.steps()) {
            final Pattern pattern = TaskDispatcher.requirePattern(step.recipe(), patternsById);
            if (activePatterns.containsKey(pattern)) {
                order.add(pattern);
            }
        }
        this.plannerPatternOrder = List.copyOf(order);
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
        updateState(TaskState.RETURNING_INTERNAL_STORAGE);
        cancelled = true;
    }

    @Override
    public TaskStatus getStatus() {
        final TaskStatusBuilder builder = new TaskStatusBuilder(getId(), state, getResource(), getAmount(), startTime);
        initialRequirements.getAll().forEach(
            requiredResource -> builder.extracting(requiredResource, initialRequirements.get(requiredResource))
        );

        for (final AbstractTaskPattern pattern : activePatterns.values()) {
            pattern.appendStatus(builder);
        }

        internalStorage.getAll().forEach(
            internalResource -> builder.stored(internalResource, internalStorage.get(internalResource))
        );
        return builder.build(progress());
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
            Map.of(),
            List.of(),
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
            // Prime cycle-safety before the first RUNNING tick to avoid one-step lag.
            recalculateStepSafeQueue();
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

        boolean changed = false;
        final var it = activePatterns.entrySet().iterator();
        while (it.hasNext()) {
            final var entry = it.next();
            final Pattern pattern = entry.getKey();
            final int budget = cycleSafeStepBudgets.getOrDefault(pattern, 0);
            if (budget <= 0) {
                continue;
            }

            final StepExecutionResult executionResult = stepPattern(
                rootStorage,
                sinkProvider,
                stepBehavior,
                listener,
                pattern,
                entry.getValue()
            );

            consumeStepBudget(pattern, executionResult.executedSteps());

            if (executionResult.result() == PatternStepResult.COMPLETED) {
                it.remove();
                cycleSafeStepBudgets.remove(pattern);
                plannerOverrideRemainingSteps.remove(pattern);
                completedPatterns.add(entry.getValue());
                queueDirty = true;
                changed = true;
            } else {
                changed |= executionResult.result() != PatternStepResult.IDLE;
            }
        }

        if (activePatterns.isEmpty()) {
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
                                            final Pattern pattern,
                                            final AbstractTaskPattern taskPattern) {
        PatternStepResult result = PatternStepResult.IDLE;
        int executedSteps = 0;
        if (!stepBehavior.canStep(pattern)) {
            return new StepExecutionResult(result, executedSteps);
        }
        final int steps = stepBehavior.getSteps(pattern);
        for (int i = 0; i < steps; ++i) {
            executedSteps++;
            final PatternStepResult stepResult = taskPattern.step(internalStorage, rootStorage, sinkProvider, listener);
            if (stepResult == PatternStepResult.COMPLETED) {
                LOGGER.debug("{} completed", pattern);
                return new StepExecutionResult(stepResult, executedSteps);
            }
            if (stepResult != PatternStepResult.IDLE) {
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
        for (final AbstractTaskPattern pattern : activePatterns.values()) {
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
        for (final AbstractTaskPattern pattern : activePatterns.values()) {
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
        final int total = activePatterns.size() + completedPatterns.size();
        if (total == 0) {
            return 1D;
        }
        return completedPatterns.size() / (double) total;
    }

    private void recalculateStepSafeQueue() {
        cycleSafeStepBudgets.clear();

        final CycleSafetyState cycleSafetyState = buildCycleSafetyState();
        final Pattern plannerOverridePattern = findPlannerOverridePattern();

        for (final Pattern pattern : plannerPatternOrder) {
            if (activePatterns.containsKey(pattern)) {
                final int budget = computeStepBudget(pattern, plannerOverridePattern, cycleSafetyState);
                if (budget > 0) {
                    cycleSafeStepBudgets.put(pattern, budget);
                }
            }
        }

        for (final Pattern pattern : activePatterns.keySet()) {
            if (!cycleSafeStepBudgets.containsKey(pattern)) {
                final int budget = computeStepBudget(pattern, plannerOverridePattern, cycleSafetyState);
                if (budget > 0) {
                    cycleSafeStepBudgets.put(pattern, budget);
                }
            }
        }

        queueDirty = false;
    }

    private int computeStepBudget(final Pattern pattern,
                                  final Pattern plannerOverridePattern,
                                  final CycleSafetyState cycleSafetyState) {
        if (isUnlimitedCycleSafe(pattern, cycleSafetyState)) {
            return Integer.MAX_VALUE;
        }
        if (pattern.equals(plannerOverridePattern)) {
            return Math.max(0, plannerOverrideRemainingSteps.getOrDefault(pattern, 0));
        }
        return 0;
    }

    private boolean isUnlimitedCycleSafe(final Pattern pattern,
                                         final CycleSafetyState cycleSafetyState) {
        final Set<ResourceKey> consumed = consumedResourcesByPattern.getOrDefault(pattern, Set.of());
        final Set<ResourceKey> conflictedConsumed = new HashSet<>();
        for (final ResourceKey resource : consumed) {
            if (cycleSafetyState.conflictedResources.contains(resource)) {
                conflictedConsumed.add(resource);
            }
        }

        if (conflictedConsumed.isEmpty()) {
            return true;
        }

        final Integer groupId = cycleSafetyState.cycleGroupByPattern.get(pattern);
        if (groupId == null) {
            return false;
        }

        final Set<ResourceKey> producedByGroup = cycleSafetyState.producedResourcesByCycleGroup
            .getOrDefault(groupId, Set.of());
        return producedByGroup.containsAll(conflictedConsumed);
    }

    private Pattern findPlannerOverridePattern() {
        for (final Pattern pattern : plannerPatternOrder) {
            if (!activePatterns.containsKey(pattern)) {
                continue;
            }
            if (plannerOverrideRemainingSteps.getOrDefault(pattern, 0) > 0) {
                return pattern;
            }
        }
        return null;
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

    private CycleSafetyState buildCycleSafetyState() {
        final Set<Pattern> active = activePatterns.keySet();
        final Map<Pattern, Set<ResourceKey>> producedResourcesByPattern = new HashMap<>();
        final Map<Pattern, Set<Pattern>> adjacency = new HashMap<>();

        for (final Pattern pattern : active) {
            producedResourcesByPattern.put(pattern, getProducedResources(pattern));
            adjacency.put(pattern, new LinkedHashSet<>());
        }

        for (final Pattern source : active) {
            final Set<ResourceKey> sourceProduces = producedResourcesByPattern.getOrDefault(source, Set.of());
            for (final Pattern target : active) {
                final Set<ResourceKey> targetConsumes = consumedResourcesByPattern.getOrDefault(target, Set.of());
                if (!sourceProduces.isEmpty() && !targetConsumes.isEmpty() && intersects(sourceProduces, targetConsumes)) {
                    adjacency.get(source).add(target);
                }
            }
        }

        final Map<Pattern, Set<Pattern>> reachable = new HashMap<>();
        for (final Pattern pattern : active) {
            reachable.put(pattern, dfsReachable(pattern, adjacency));
        }

        final Map<Pattern, Integer> groupByPattern = new HashMap<>();
        final Map<Integer, Set<Pattern>> groups = new HashMap<>();
        int groupId = 0;
        for (final Pattern pattern : active) {
            if (groupByPattern.containsKey(pattern)) {
                continue;
            }
            final Set<Pattern> group = new LinkedHashSet<>();
            group.add(pattern);
            for (final Pattern other : active) {
                if (pattern.equals(other)) {
                    continue;
                }
                if (reachable.getOrDefault(pattern, Set.of()).contains(other)
                    && reachable.getOrDefault(other, Set.of()).contains(pattern)) {
                    group.add(other);
                }
            }
            for (final Pattern member : group) {
                groupByPattern.put(member, groupId);
            }
            groups.put(groupId, group);
            groupId++;
        }

        final Map<Integer, Set<ResourceKey>> producedByCycleGroup = new HashMap<>();
        final Set<Pattern> cyclePatterns = new HashSet<>();
        for (final var entry : groups.entrySet()) {
            final int id = entry.getKey();
            final Set<Pattern> group = entry.getValue();
            final boolean selfCycle = group.size() == 1
                && adjacency.getOrDefault(group.iterator().next(), Set.of()).contains(group.iterator().next());
            final boolean isCycle = group.size() > 1 || selfCycle;
            if (!isCycle) {
                continue;
            }
            final Set<ResourceKey> produced = new HashSet<>();
            for (final Pattern pattern : group) {
                cyclePatterns.add(pattern);
                produced.addAll(producedResourcesByPattern.getOrDefault(pattern, Set.of()));
            }
            producedByCycleGroup.put(id, produced);
        }

        final Set<ResourceKey> producedInCycles = new HashSet<>();
        final Set<ResourceKey> consumedInCycles = new HashSet<>();
        for (final Pattern pattern : cyclePatterns) {
            producedInCycles.addAll(producedResourcesByPattern.getOrDefault(pattern, Set.of()));
            consumedInCycles.addAll(consumedResourcesByPattern.getOrDefault(pattern, Set.of()));
        }
        final Set<ResourceKey> conflicted = new HashSet<>(producedInCycles);
        conflicted.retainAll(consumedInCycles);

        return new CycleSafetyState(groupByPattern, producedByCycleGroup, conflicted);
    }

    private static Set<Pattern> dfsReachable(final Pattern start,
                                             final Map<Pattern, Set<Pattern>> adjacency) {
        final Set<Pattern> visited = new LinkedHashSet<>();
        final List<Pattern> stack = new ArrayList<>();
        stack.add(start);
        while (!stack.isEmpty()) {
            final Pattern current = stack.removeLast();
            if (!visited.add(current)) {
                continue;
            }
            for (final Pattern next : adjacency.getOrDefault(current, Set.of())) {
                if (!visited.contains(next)) {
                    stack.add(next);
                }
            }
        }
        return visited;
    }

    private static boolean intersects(final Set<ResourceKey> left,
                                      final Set<ResourceKey> right) {
        for (final ResourceKey resource : left) {
            if (right.contains(resource)) {
                return true;
            }
        }
        return false;
    }

    private static Set<ResourceKey> getProducedResources(final Pattern pattern) {
        final Set<ResourceKey> produced = new LinkedHashSet<>();
        pattern.layout().outputs().forEach(output -> produced.add(output.resource()));
        pattern.layout().byproducts().forEach(byproduct -> produced.add(byproduct.resource()));
        return produced;
    }

    private static CyclicMergedPlan toMergedPlan(final ResourceKey requestedResource,
                                                  final long requestedAmount,
                                                  final List<RecipeApplicationStep> steps,
                                                  final Map<UUID, Pattern> patternsById,
                                                  final Pattern rootPattern) {
        final Map<Pattern, MutablePatternPlan> mutablePlans = new LinkedHashMap<>();

        for (final RecipeApplicationStep step : steps) {
            final Pattern stepPattern = TaskDispatcher.requirePattern(step.recipe(), patternsById);
            final boolean root = stepPattern.equals(rootPattern);
            final TaskPlan stepPlan = TaskDispatcher.translateLpStepToTaskPlan(requestedResource, -1, step, patternsById, root);
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
            final Map<Integer, Map<ResourceKey, Long>> immutableIngredients = new LinkedHashMap<>();

            for (final var ingredientEntry : mutable.ingredients.entrySet()) {
                immutableIngredients.put(ingredientEntry.getKey(), Map.copyOf(ingredientEntry.getValue()));
                ingredientEntry.getValue().forEach((resource, amount) -> totalInputs.merge(resource, amount, Long::sum));
            }

            final Set<ResourceKey> consumedResources = new LinkedHashSet<>();
            immutableIngredients.values().forEach(ingredientResources -> consumedResources.addAll(ingredientResources.keySet()));
            consumedResourcesByPattern.put(pattern, Set.copyOf(consumedResources));

            final long totalIterations = mutable.iterations;
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

    private record CyclicMergedPlan(Map<Pattern, TaskPlan.PatternPlan> patternPlans,
                                    List<ResourceAmount> initialRequirements,
                                    Map<Pattern, Set<ResourceKey>> consumedResourcesByPattern,
                                    Map<Pattern, Integer> plannerOverrideInitialSteps) {
    }

    private record CycleSafetyState(Map<Pattern, Integer> cycleGroupByPattern,
                                    Map<Integer, Set<ResourceKey>> producedResourcesByCycleGroup,
                                    Set<ResourceKey> conflictedResources) {
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