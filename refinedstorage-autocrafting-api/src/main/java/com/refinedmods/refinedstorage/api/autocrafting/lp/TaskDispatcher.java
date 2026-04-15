package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatusBuilder;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class TaskDispatcher {
    private TaskDispatcher() {
    }

    public static Optional<TaskId> addTask(final ResourceKey resource,
                                           final long amount,
                                           final Actor actor,
                                           final RecipeApplicationPath path,
                                           final Collection<Pattern> patterns,
                                           final boolean notify,
                                           final SingleStepTaskCreator singleStepTaskCreator,
                                           final PatternTaskSubmitter patternTaskSubmitter,
                                           final Predicate<Pattern> hasProvider) {
        final Map<UUID, Pattern> patternsById = indexPatterns(patterns);

        if (path.steps().size() == 1) {
            final TaskPlan singleStepPlan = toSingleStepPlan(
                resource,
                amount,
                path.steps().getFirst(),
                patternsById,
                true
            );
            return Optional.of(singleStepTaskCreator.create(actor, singleStepPlan, notify));
        }

        final Pattern rootPattern = findRootPattern(resource, path.steps(), patternsById);
        if (rootPattern == null) {
            return Optional.empty();
        }
        if (!hasProvider.test(rootPattern)) {
            return Optional.empty();
        }

        final Task dispatcher = new DispatcherTask(
            resource,
            amount,
            actor,
            notify,
            path,
            patternsById,
            rootPattern,
            hasProvider
        );
        if (!patternTaskSubmitter.submit(rootPattern, dispatcher)) {
            return Optional.empty();
        }
        return Optional.of(dispatcher.getId());
    }

    @FunctionalInterface
    public interface SingleStepTaskCreator {
        TaskId create(Actor actor, TaskPlan plan, boolean notify);
    }

    @FunctionalInterface
    public interface PatternTaskSubmitter {
        boolean submit(Pattern pattern, Task task);
    }

    private static Pattern findRootPattern(final ResourceKey resource,
                                           final List<RecipeApplicationStep> steps,
                                           final Map<UUID, Pattern> patternsById) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            final Pattern pattern = patternsById.get(steps.get(index).recipe().sourcePatternId());
            if (pattern == null) {
                continue;
            }
            final boolean producesResource = pattern.layout().outputs().stream()
                .anyMatch(output -> output.resource().equals(resource));
            if (producesResource) {
                return pattern;
            }
        }
        return null;
    }

    private static TaskPlan toSingleStepPlan(final ResourceKey requestedResource,
                                             final long requestedAmount,
                                             final RecipeApplicationStep step,
                                             final Map<UUID, Pattern> patternsById,
                                             final boolean root) {
        final Pattern pattern = requirePattern(step.recipe(), patternsById);
        final long iterations = step.timesApplied();

        final Map<ResourceKey, Long> availablePerIteration = new LinkedHashMap<>();
        for (final var entry : step.recipe().input()) {
            availablePerIteration.put(toConcreteResourceKey(entry.getKey()), entry.getValue());
        }

        final Map<Integer, Map<ResourceKey, Long>> ingredients = new LinkedHashMap<>();
        final List<ResourceAmount> initialRequirements = new ArrayList<>();
        for (int ingredientIndex = 0; ingredientIndex < pattern.layout().ingredients().size(); ingredientIndex++) {
            final var ingredient = pattern.layout().ingredients().get(ingredientIndex);
            ResourceKey resource = ingredient.inputs().getFirst();
            for (final ResourceKey option : ingredient.inputs()) {
                final long available = availablePerIteration.getOrDefault(option, 0L);
                if (available >= ingredient.amount()) {
                    resource = option;
                    availablePerIteration.put(option, available - ingredient.amount());
                    break;
                }
            }
            final long totalAmount = ingredient.amount() * iterations;
            ingredients.put(ingredientIndex, Map.of(resource, totalAmount));
            initialRequirements.add(new ResourceAmount(resource, totalAmount));
        }

        final Map<Pattern, TaskPlan.PatternPlan> patterns = Map.of(
            pattern,
            new TaskPlan.PatternPlan(root, iterations, Map.copyOf(ingredients))
        );

        final ResourceKey outputResource = pattern.layout().outputs().isEmpty()
            ? requestedResource
            : pattern.layout().outputs().getFirst().resource();
        final long outputAmount = pattern.layout().outputs().isEmpty()
            ? iterations
            : pattern.layout().outputs().getFirst().amount() * iterations;
        final long taskAmount = requestedAmount > 0 ? requestedAmount : outputAmount;

        return new TaskPlan(
            outputResource,
            taskAmount,
            pattern,
            patterns,
            List.copyOf(initialRequirements)
        );
    }

    private static TaskPlan createDispatcherPlan(final ResourceKey resource,
                                                 final long amount,
                                                 final Pattern rootPattern) {
        return new TaskPlan(
            resource,
            amount,
            rootPattern,
            Map.of(rootPattern, new TaskPlan.PatternPlan(true, 1, Map.of())),
            List.of()
        );
    }

    private static final class DispatcherTask extends TaskImpl {
        private final long startTime = System.currentTimeMillis();
        private final int totalSteps;
        private final List<RecipeApplicationStep> pendingSteps;
        private final Map<UUID, Pattern> patternsById;
        private final MutableResourceList bufferedInternalStorage = MutableResourceListImpl.create();
        private final Map<TaskId, DispatchedSubTask> activeSubTasks = new LinkedHashMap<>();
        private final boolean strictOrdering;
        private final Predicate<Pattern> hasProvider;
        private TaskState state = TaskState.READY;
        private boolean cancelled;

        private DispatcherTask(final ResourceKey resource,
                               final long amount,
                               final Actor actor,
                               final boolean notify,
                               final RecipeApplicationPath path,
                               final Map<UUID, Pattern> patternsById,
                               final Pattern rootPattern,
                               final Predicate<Pattern> hasProvider) {
            super(createDispatcherPlan(resource, amount, rootPattern), actor, notify);
            this.strictOrdering = hasRecipeCycles(path.steps());
            this.pendingSteps = new ArrayList<>(path.steps());
            this.totalSteps = path.steps().size();
            this.patternsById = Map.copyOf(patternsById);
            this.hasProvider = hasProvider;
        }

        @Override
        public boolean shouldNotify() {
            return super.shouldNotify() && !cancelled;
        }

        @Override
        public TaskState getState() {
            return state;
        }

        @Override
        public boolean step(final RootStorage rootStorage,
                            final ExternalPatternSinkProvider sinkProvider,
                            final StepBehavior stepBehavior,
                            final TaskListener listener) {
            boolean changed = pruneCompletedSubTasks();

            if (state == TaskState.READY) {
                state = TaskState.RUNNING;
                changed = true;
            }

            if (cancelled) {
                cancelActiveSubTasks();
                if (activeSubTasks.isEmpty()) {
                    state = TaskState.COMPLETED;
                    return true;
                }
                state = TaskState.RETURNING_INTERNAL_STORAGE;
                return changed;
            }

            if (state == TaskState.RUNNING) {
                final Set<TaskId> existingSubTasks = new HashSet<>(activeSubTasks.keySet());
                changed |= stepSpecificSubTasks(existingSubTasks, rootStorage, sinkProvider, stepBehavior, listener);
                changed |= pruneCompletedSubTasks();

                if (strictOrdering) {
                    changed |= dispatchStrict(rootStorage);
                } else {
                    changed |= dispatchRelaxed(rootStorage);
                }

                final Set<TaskId> newSubTasks = new HashSet<>(activeSubTasks.keySet());
                newSubTasks.removeAll(existingSubTasks);
                changed |= stepSpecificSubTasks(newSubTasks, rootStorage, sinkProvider, stepBehavior, listener);
                changed |= pruneCompletedSubTasks();

                if (pendingSteps.isEmpty() && activeSubTasks.isEmpty()) {
                    state = bufferedInternalStorage.isEmpty()
                        ? TaskState.COMPLETED
                        : TaskState.RETURNING_INTERNAL_STORAGE;
                    changed = true;
                }
            } else if (state == TaskState.RETURNING_INTERNAL_STORAGE) {
                changed |= returnBufferedInternalStorage(rootStorage);
            }

            return changed;
        }

        private boolean returnBufferedInternalStorage(final RootStorage rootStorage) {
            boolean returnedAny = false;
            boolean returnedAll = true;
            final Set<ResourceKey> resources = new HashSet<>(bufferedInternalStorage.getAll());
            for (final ResourceKey resource : resources) {
                final long amount = bufferedInternalStorage.get(resource);
                final long inserted = rootStorage.insert(resource, amount, Action.EXECUTE, Actor.EMPTY);
                if (inserted > 0) {
                    bufferedInternalStorage.remove(resource, inserted);
                    returnedAny = true;
                }
                if (inserted != amount) {
                    returnedAll = false;
                }
            }
            if (returnedAll) {
                state = TaskState.COMPLETED;
                return true;
            }
            return returnedAny;
        }

        private boolean pruneCompletedSubTasks() {
            boolean changed = false;
            final var it = activeSubTasks.entrySet().iterator();
            while (it.hasNext()) {
                final var entry = it.next();
                final TaskState subTaskState = entry.getValue().task().getState();
                if (subTaskState == TaskState.COMPLETED
                    || (!entry.getValue().root() && subTaskState == TaskState.RETURNING_INTERNAL_STORAGE)) {
                    final MutableResourceList subTaskInternalStorage = entry.getValue()
                        .task()
                        .createSnapshot()
                        .copyInternalStorage();
                    subTaskInternalStorage.getAll().forEach(resource ->
                        bufferedInternalStorage.add(resource, subTaskInternalStorage.get(resource)));
                    it.remove();
                    changed = true;
                }
            }
            return changed;
        }

        private void cancelActiveSubTasks() {
            final List<DispatchedSubTask> snapshot = List.copyOf(activeSubTasks.values());
            for (final DispatchedSubTask subTask : snapshot) {
                subTask.task().cancel();
            }
        }

        private boolean stepSpecificSubTasks(final Set<TaskId> taskIds,
                                             final RootStorage rootStorage,
                                             final ExternalPatternSinkProvider sinkProvider,
                                             final StepBehavior stepBehavior,
                                             final TaskListener listener) {
            boolean changed = false;
            for (final TaskId taskId : taskIds) {
                final DispatchedSubTask subTask = activeSubTasks.get(taskId);
                if (subTask == null) {
                    continue;
                }
                changed |= subTask.task().step(rootStorage, sinkProvider, stepBehavior, listener);
            }
            return changed;
        }

        private boolean dispatchStrict(final RootStorage rootStorage) {
            if (!activeSubTasks.isEmpty() || pendingSteps.isEmpty()) {
                return false;
            }

            final RecipeApplicationStep next = pendingSteps.getFirst();
            final Map<ResourceKey, Long> available = availableWithReservations(rootStorage);
            final long dispatchIterations = maxDispatchableIterations(next, available);
            if (dispatchIterations <= 0) {
                return false;
            }
            final Map<ResourceKey, Long> requirements = stepRequirements(next, dispatchIterations);

            final Optional<TaskId> dispatched = dispatchSubTask(next, dispatchIterations, requirements);
            if (dispatched.isEmpty()) {
                return false;
            }

            updatePendingStepAfterDispatch(0, next, dispatchIterations);
            return true;
        }

        private boolean dispatchRelaxed(final RootStorage rootStorage) {
            if (pendingSteps.isEmpty()) {
                return false;
            }

            final Map<ResourceKey, Long> available = availableWithReservations(rootStorage);
            boolean changed = false;
            boolean dispatchedAny;
            do {
                dispatchedAny = false;
                for (int index = 0; index < pendingSteps.size(); index++) {
                    final RecipeApplicationStep step = pendingSteps.get(index);
                    final long dispatchIterations = maxDispatchableIterations(step, available);
                    if (dispatchIterations <= 0) {
                        continue;
                    }
                    final Map<ResourceKey, Long> requirements = stepRequirements(step, dispatchIterations);

                    final Optional<TaskId> dispatched = dispatchSubTask(step, dispatchIterations, requirements);
                    if (dispatched.isEmpty()) {
                        continue;
                    }

                    consume(requirements, available);
                    updatePendingStepAfterDispatch(index, step, dispatchIterations);
                    changed = true;
                    dispatchedAny = true;
                    break;
                }
            } while (dispatchedAny);

            return changed;
        }

        private void updatePendingStepAfterDispatch(final int index,
                                                    final RecipeApplicationStep originalStep,
                                                    final long dispatchedIterations) {
            final long remainingIterations = originalStep.timesApplied() - dispatchedIterations;
            if (remainingIterations <= 0) {
                pendingSteps.remove(index);
                return;
            }
            pendingSteps.set(index, new RecipeApplicationStep(originalStep.recipe(), remainingIterations));
        }

        private Optional<TaskId> dispatchSubTask(final RecipeApplicationStep step,
                                                 final long dispatchIterations,
                                                 final Map<ResourceKey, Long> requirements) {
            final RecipeApplicationStep dispatchedStep = new RecipeApplicationStep(step.recipe(), dispatchIterations);
            final boolean root = step.recipe().output().getAmount(new MultiResourceKey(List.of(getResource()))) > 0L;
            final TaskPlan plan = toSingleStepPlan(getResource(), -1, dispatchedStep, patternsById, root);
            final Pattern pattern = requirePattern(step.recipe(), patternsById);

            if (!hasProvider.test(pattern)) {
                return Optional.empty();
            }

            final SeededSubTask seededSubTask = createSeededSubTask(plan, requirements);
            activeSubTasks.put(
                seededSubTask.task().getId(),
                new DispatchedSubTask(seededSubTask.task(), seededSubTask.remainingRequirements(), root)
            );
            return Optional.of(seededSubTask.task().getId());
        }

        private SeededSubTask createSeededSubTask(final TaskPlan plan,
                                                  final Map<ResourceKey, Long> requirements) {
            final MutableResourceList seededInternalStorage = MutableResourceListImpl.create();
            final MutableResourceList remainingInitialRequirements = MutableResourceListImpl.create();

            requirements.forEach((resource, amountNeeded) -> {
                final long bufferedAmount = bufferedInternalStorage.get(resource);
                final long consumedFromBuffer = Math.min(bufferedAmount, amountNeeded);
                if (consumedFromBuffer > 0) {
                    bufferedInternalStorage.remove(resource, consumedFromBuffer);
                    seededInternalStorage.add(resource, consumedFromBuffer);
                }

                final long remaining = amountNeeded - consumedFromBuffer;
                if (remaining > 0) {
                    remainingInitialRequirements.add(resource, remaining);
                }
            });

            final TaskImpl baseTask = new TaskImpl(plan, getActor(), false);
            final TaskSnapshot baseSnapshot = baseTask.createSnapshot();
            final TaskState initialState = remainingInitialRequirements.isEmpty() ? TaskState.RUNNING : TaskState.READY;
            final TaskImpl task = new TaskImpl(new TaskSnapshot(
                baseSnapshot.id(),
                baseSnapshot.resource(),
                baseSnapshot.amount(),
                baseSnapshot.actor(),
                baseSnapshot.notifyActor(),
                baseSnapshot.startTime(),
                baseSnapshot.patterns(),
                baseSnapshot.completedPatterns(),
                remainingInitialRequirements.copy(),
                seededInternalStorage.copy(),
                initialState,
                false
            ));

            final Map<ResourceKey, Long> remainingRequirements = new HashMap<>();
            remainingInitialRequirements.getAll().forEach(resource ->
                remainingRequirements.put(resource, remainingInitialRequirements.get(resource)));
            return new SeededSubTask(task, remainingRequirements);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public TaskStatus getStatus() {
            final TaskStatusBuilder builder = new TaskStatusBuilder(
                getId(),
                state,
                getResource(),
                getAmount(),
                startTime
            );
            if (strictOrdering) {
                builder.processing(getResource(), Math.max(1, activeSubTasks.size() + pendingSteps.size()), null);
            } else if (!pendingSteps.isEmpty()) {
                builder.scheduled(getResource(), pendingSteps.size());
            }
            return builder.build(progress());
        }

        @Override
        public TaskSnapshot createSnapshot() {
            final MutableResourceList internalStorage = MutableResourceListImpl.create();
            final MutableResourceList initialRequirements = MutableResourceListImpl.create();

            bufferedInternalStorage.getAll().forEach(resource ->
                internalStorage.add(resource, bufferedInternalStorage.get(resource)));

            for (final DispatchedSubTask subTask : activeSubTasks.values()) {
                final TaskSnapshot snapshot = subTask.task().createSnapshot();
                final MutableResourceList subTaskInternalStorage = snapshot.copyInternalStorage();
                subTaskInternalStorage.getAll().forEach(resource ->
                    internalStorage.add(resource, subTaskInternalStorage.get(resource)));
                snapshot.initialRequirements().getAll().forEach(resource ->
                    initialRequirements.add(resource, snapshot.initialRequirements().get(resource)));
            }

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

        @Override
        public long beforeInsert(final ResourceKey insertedResource, final long insertedAmount) {
            if (cancelled) {
                return 0;
            }

            long intercepted = 0;
            for (final DispatchedSubTask subTask : activeSubTasks.values()) {
                final long available = insertedAmount - intercepted;
                intercepted += subTask.task().beforeInsert(insertedResource, available);
                if (intercepted == insertedAmount) {
                    break;
                }
            }

            final long remaining = insertedAmount - intercepted;
            final long pendingNeed = getPendingRequirement(insertedResource)
                - bufferedInternalStorage.get(insertedResource)
                - getActiveInternalStorage(insertedResource);
            if (remaining > 0 && pendingNeed > 0) {
                final long buffered = Math.min(remaining, pendingNeed);
                bufferedInternalStorage.add(insertedResource, buffered);
                intercepted += buffered;
            }
            return intercepted;
        }

        @Override
        public long afterInsert(final ResourceKey insertedResource, final long insertedAmount) {
            long reserved = 0;
            for (final DispatchedSubTask subTask : activeSubTasks.values()) {
                final long available = insertedAmount - reserved;
                reserved += subTask.task().afterInsert(insertedResource, available);
                if (reserved == insertedAmount) {
                    return reserved;
                }
            }
            return reserved;
        }

        @Override
        public void changed(final MutableResourceList.OperationResult change) {
            // no op
        }

        private double progress() {
            if (totalSteps == 0) {
                return 1D;
            }
            final int remaining = pendingSteps.size() + activeSubTasks.size();
            final int done = Math.max(0, totalSteps - remaining);
            return done / (double) totalSteps;
        }

        private Map<ResourceKey, Long> availableWithReservations(final RootStorage rootStorage) {
            final Map<ResourceKey, Long> available = new HashMap<>();
            for (final ResourceAmount resourceAmount : rootStorage.getAll()) {
                available.put(resourceAmount.resource(), resourceAmount.amount());
            }
            bufferedInternalStorage.getAll().forEach(resource ->
                available.put(resource, available.getOrDefault(resource, 0L) + bufferedInternalStorage.get(resource)));
            for (final DispatchedSubTask subTask : activeSubTasks.values()) {
                consume(subTask.requirements(), available);
            }
            return available;
        }

        private long getPendingRequirement(final ResourceKey resource) {
            long amount = 0;
            for (final RecipeApplicationStep step : pendingSteps) {
                for (final var entry : step.recipe().input()) {
                    if (toConcreteResourceKey(entry.getKey()).equals(resource)) {
                        amount += entry.getValue() * step.timesApplied();
                    }
                }
            }
            return amount;
        }

        private long getActiveInternalStorage(final ResourceKey resource) {
            long amount = 0;
            for (final DispatchedSubTask subTask : activeSubTasks.values()) {
                amount += subTask.task().createSnapshot().copyInternalStorage().get(resource);
            }
            return amount;
        }

        private static void consume(final Map<ResourceKey, Long> requirements,
                                    final Map<ResourceKey, Long> available) {
            for (final Map.Entry<ResourceKey, Long> entry : requirements.entrySet()) {
                available.put(entry.getKey(), available.getOrDefault(entry.getKey(), 0L) - entry.getValue());
            }
        }

        private static long maxDispatchableIterations(final RecipeApplicationStep step,
                                                      final Map<ResourceKey, Long> available) {
            long maxIterations = step.timesApplied();
            for (final var entry : step.recipe().input()) {
                final long perIterationAmount = entry.getValue();
                if (perIterationAmount <= 0) {
                    continue;
                }
                final long availableAmount = available.getOrDefault(toConcreteResourceKey(entry.getKey()), 0L);
                maxIterations = Math.min(maxIterations, availableAmount / perIterationAmount);
                if (maxIterations <= 0) {
                    return 0;
                }
            }
            return Math.max(0, maxIterations);
        }

        private static Map<ResourceKey, Long> stepRequirements(final RecipeApplicationStep step,
                                                               final long iterations) {
            final Map<ResourceKey, Long> requirements = new HashMap<>();
            for (final var entry : step.recipe().input()) {
                requirements.merge(toConcreteResourceKey(entry.getKey()), entry.getValue() * iterations, Long::sum);
            }
            return requirements;
        }

        private record DispatchedSubTask(TaskImpl task, Map<ResourceKey, Long> requirements, boolean root) {
        }

        private record SeededSubTask(TaskImpl task, Map<ResourceKey, Long> remainingRequirements) {
        }
    }

    private static Map<UUID, Pattern> indexPatterns(final Collection<Pattern> patterns) {
        final Map<UUID, Pattern> patternsById = new LinkedHashMap<>();
        for (final Pattern pattern : patterns) {
            patternsById.put(pattern.id(), pattern);
        }
        return patternsById;
    }

    private static Pattern requirePattern(final ConcreteRecipe recipe, final Map<UUID, Pattern> patternsById) {
        final Pattern pattern = patternsById.get(recipe.sourcePatternId());
        if (pattern != null) {
            return pattern;
        }
        throw new IllegalStateException("Missing pattern for recipe " + recipe.recipeId());
    }

    private static ResourceKey toConcreteResourceKey(final MultiResourceKey resourceKey) {
        if (!resourceKey.members().isEmpty()) {
            return resourceKey.members().getFirst();
        }
        throw new IllegalStateException("MultiResourceKey has no members: " + resourceKey);
    }

    private static boolean hasRecipeCycles(final List<RecipeApplicationStep> steps) {
        final Map<UUID, ConcreteRecipe> recipesById = new LinkedHashMap<>();
        for (final RecipeApplicationStep step : steps) {
            recipesById.putIfAbsent(step.recipe().recipeId(), step.recipe());
        }
        return !RecipeAnalyzer.detectRecipeCycles(new ArrayList<>(recipesById.values())).cycles().isEmpty();
    }
}
