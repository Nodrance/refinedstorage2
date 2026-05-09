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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class CyclicTaskImpl extends TaskImpl {
    private static final Logger LOGGER = LoggerFactory.getLogger(CyclicTaskImpl.class);
    
    private final long startTime;
    private final int totalSteps;
    private final List<RecipeApplicationStep> pendingSteps;
    private final Map<UUID, Pattern> patternsById;
    private final Pattern rootPattern;
    private final ResourcePool peakResourceUsage;
    private final MutableResourceList bufferedInternalStorage = MutableResourceListImpl.create();
    private final Map<Integer, StepExecution> activeSteps = new LinkedHashMap<>();
    private final Predicate<Pattern> hasProvider;
    private TaskState state = TaskState.READY;
    private boolean cancelled;

    // Different from TaskImpl constructors: this one initializes LP-specific cyclic runtime state.
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
        this.pendingSteps = new ArrayList<>(path.steps());
        this.totalSteps = path.steps().size();
        this.patternsById = Map.copyOf(patternsById);
        this.rootPattern = rootPattern;
        this.peakResourceUsage = path.peakResourceUsage().copy();
        this.hasProvider = hasProvider;
    }

    // Same as TaskImpl: exposes the current state for the task lifecycle.
    @Override
    public TaskState getState() {
        return state;
    }

    // Different from TaskImpl: local state writer because CyclicTaskImpl keeps its own state field.
    private void updateState(final TaskState newState) {
        this.state = newState;
    }

    // Different from TaskImpl: same state machine shape, but READY/EXTRACTING funnel directly into cyclic staged stepping.
    @Override
    public boolean step(final RootStorage rootStorage,
                        final ExternalPatternSinkProvider sinkProvider,
                        final StepBehavior stepBehavior,
                        final TaskListener listener) {
        return switch (state) {
            case READY -> startTask(rootStorage, sinkProvider, stepBehavior, listener);
            case EXTRACTING_INITIAL_RESOURCES -> extractInitialResourcesAndTryStartRunningTask(
                rootStorage,
                sinkProvider,
                stepBehavior,
                listener
            );
            case RUNNING -> stepPatterns(rootStorage, sinkProvider, stepBehavior, listener);
            case RETURNING_INTERNAL_STORAGE -> returnInternalStorageAndTryCompleteTask(rootStorage);
            case COMPLETED -> false;
        };
    }

    // Same as TaskImpl: delegate to base cancel behavior and mark this task as cancelled.
    @Override
    public void cancel() {
        super.cancel();
        cancelled = true;
    }

    // Different from TaskImpl: reports progress/status over dynamic pending/active cyclic step executions.
    @Override
    public TaskStatus getStatus() {
        final TaskStatusBuilder builder = new TaskStatusBuilder(
            getId(),
            state,
            getResource(),
            getAmount(),
            startTime
        );
        if (!pendingSteps.isEmpty() || !activeSteps.isEmpty()) {
            builder.processing(getResource(), Math.max(1, activeSteps.size() + pendingSteps.size()), null);
        }
        
        // Append status from all active step patterns
        for (final StepExecution stepExecution : activeSteps.values()) {
            for (final AbstractTaskPattern pattern : stepExecution.patterns.values()) {
                pattern.appendStatus(builder);
            }
        }
        
        // Add buffered internal storage
        bufferedInternalStorage.getAll().forEach(resource ->
            builder.stored(resource, bufferedInternalStorage.get(resource)));

        return builder.build(progress());
    }

    // Different from TaskImpl: snapshot stores cyclic runtime queues/buffers instead of static plan/completed-pattern snapshots.
    @Override
    public TaskSnapshot createSnapshot() {
        final MutableResourceList allInternalStorage = MutableResourceListImpl.create();
        final MutableResourceList allInitialRequirements = MutableResourceListImpl.create();

        bufferedInternalStorage.getAll().forEach(resource ->
            allInternalStorage.add(resource, bufferedInternalStorage.get(resource)));

        for (final StepExecution stepExecution : activeSteps.values()) {
            stepExecution.internalStorage.getAll().forEach(resource ->
                allInternalStorage.add(resource, stepExecution.internalStorage.get(resource)));
            stepExecution.initialRequirements.getAll().forEach(resource ->
                allInitialRequirements.add(resource, stepExecution.initialRequirements.get(resource)));
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
            allInitialRequirements.copy(),
            allInternalStorage.copy(),
            state,
            cancelled
        );
    }

    // Different from TaskImpl: pulls peak resources upfront, then transitions into cyclic RUNNING staged execution.
    // Like TaskImpl.startTask, does NOT step patterns — stepping happens on the next doWork() call.
    private boolean startTask(final RootStorage rootStorage,
                              final ExternalPatternSinkProvider sinkProvider,
                              final StepBehavior stepBehavior,
                              final TaskListener listener) {
        pullPeakResources(rootStorage);
        updateState(TaskState.RUNNING);
        return !bufferedInternalStorage.isEmpty();
    }
    
    // Different from TaskImpl: pulls max required resources upfront based on peak resource usage computation.
    private void pullPeakResources(final RootStorage rootStorage) {
        for (final var entry : peakResourceUsage.asMap().entrySet()) {
            final MultiResourceKey multiKey = entry.getKey();
            final long needed = entry.getValue();
            final ResourceKey resource = RecipeDesanitizer.toSanitizedResourceKey(multiKey);
            final long extracted = rootStorage.extract(resource, needed, Action.EXECUTE, Actor.EMPTY);
            if (extracted > 0) {
                bufferedInternalStorage.add(resource, extracted);
                LOGGER.debug("Pulled {}x {} for cyclic task", extracted, resource);
            }
            if (extracted < needed) {
                LOGGER.debug("Could not pull full amount: requested {}x {}, got {}x {}", 
                    needed, resource, extracted, resource);
            }
        }
    }

    // Different from TaskImpl: cyclic mode does not perform a separate upfront extraction phase.
    private boolean extractInitialResourcesAndTryStartRunningTask(final RootStorage rootStorage,
                                                                  final ExternalPatternSinkProvider sinkProvider,
                                                                  final StepBehavior stepBehavior,
                                                                  final TaskListener listener) {
        updateState(TaskState.RUNNING);
        return true;
    }

    // Different from TaskImpl: dispatches one next fragment (serialized), then steps all active patterns once.
    // Like TaskImpl, each doWork() call steps patterns exactly once.
    private boolean stepPatterns(final RootStorage rootStorage,
                                 final ExternalPatternSinkProvider sinkProvider,
                                 final StepBehavior stepBehavior,
                                 final TaskListener listener) {
        boolean changed = pruneCompletedSteps();

        if (cancelled) {
            changed |= clearActiveSteps();
            if (!bufferedInternalStorage.isEmpty()) {
                updateState(TaskState.RETURNING_INTERNAL_STORAGE);
                return true;
            }
            updateState(TaskState.COMPLETED);
            return true;
        }

        // Dispatch the next pending step when no active steps remain (serialized to preserve LP ordering).
        changed |= dispatchNextStep(rootStorage);

        // Step all active patterns once — mirrors TaskImpl's single-step-per-doWork contract.
        changed |= stepActivePatterns(rootStorage, sinkProvider, stepBehavior, listener);

        final boolean completedAnyStep = pruneCompletedSteps();
        changed |= completedAnyStep;

        // Mirror TaskImpl behavior: if a step completed in this tick, immediately dispatch and step
        // the next step once in the same doWork() call.
        if (completedAnyStep) {
            final boolean dispatchedAnotherStep = dispatchNextStep(rootStorage);
            changed |= dispatchedAnotherStep;
            if (dispatchedAnotherStep) {
                changed |= stepActivePatterns(rootStorage, sinkProvider, stepBehavior, listener);
                changed |= pruneCompletedSteps();
            }
        }

        if (pendingSteps.isEmpty() && activeSteps.isEmpty()) {
            updateState(bufferedInternalStorage.isEmpty() ? TaskState.COMPLETED : TaskState.RETURNING_INTERNAL_STORAGE);
            changed = true;
        }

        return changed;
    }

    // Different from TaskImpl: same stepping contract, but operates on a StepExecution-local internal storage buffer.
    private PatternStepResult stepPattern(final RootStorage rootStorage,
                                          final ExternalPatternSinkProvider sinkProvider,
                                          final StepBehavior stepBehavior,
                                          final TaskListener listener,
                                          final StepExecution stepExecution,
                                          final Map.Entry<Pattern, AbstractTaskPattern> pattern) {
        PatternStepResult result = PatternStepResult.IDLE;
        if (!stepBehavior.canStep(pattern.getKey())) {
            return result;
        }
        final int steps = stepBehavior.getSteps(pattern.getKey());
        for (int i = 0; i < steps; ++i) {
            final PatternStepResult stepResult = pattern.getValue().step(
                stepExecution.internalStorage,
                rootStorage,
                sinkProvider,
                listener
            );
            if (stepResult == PatternStepResult.COMPLETED) {
                return stepResult;
            } else if (stepResult != PatternStepResult.IDLE) {
                result = PatternStepResult.RUNNING;
            }
        }
        return result;
    }

    // Different from TaskImpl: returns the cyclic buffered internal storage pool, not TaskImpl's single internalStorage field.
    private boolean returnInternalStorageAndTryCompleteTask(final RootStorage rootStorage) {
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
            updateState(TaskState.COMPLETED);
            return true;
        }
        return returnedAny;
    }

    // Different from TaskImpl: intercepts for active cyclic fragments and also buffers for pending cyclic demand.
    @Override
    public long beforeInsert(final ResourceKey insertedResource, final long insertedAmount) {
        if (cancelled) {
            return 0;
        }

        long intercepted = 0;
        for (final StepExecution stepExecution : activeSteps.values()) {
            final long interceptedBeforeStep = intercepted;
            for (final AbstractTaskPattern pattern : stepExecution.patterns.values()) {
                final long available = insertedAmount - intercepted;
                intercepted += pattern.beforeInsert(insertedResource, available);
                if (intercepted == insertedAmount) {
                    break;
                }
            }

            final long interceptedByStep = intercepted - interceptedBeforeStep;
            if (interceptedByStep > 0) {
                bufferedInternalStorage.add(insertedResource, interceptedByStep);
            }
            if (intercepted == insertedAmount) {
                return intercepted;
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

    // Different from TaskImpl: reserves against active cyclic fragments instead of TaskImpl's static pattern set.
    @Override
    public long afterInsert(final ResourceKey insertedResource, final long insertedAmount) {
        long reserved = 0;
        for (final StepExecution stepExecution : activeSteps.values()) {
            for (final AbstractTaskPattern pattern : stepExecution.patterns.values()) {
                final long available = insertedAmount - reserved;
                reserved += pattern.afterInsert(insertedResource, available);
                if (reserved == insertedAmount) {
                    return reserved;
                }
            }
        }
        return reserved;
    }

    // Same as TaskImpl: no-op listener hook.
    @Override
    public void changed(final MutableResourceList.OperationResult change) {
        // no op
    }

    // Different from TaskImpl: progress is based on pending+active cyclic steps rather than weighted pattern completion.
    private double progress() {
        if (totalSteps == 0) {
            return 1D;
        }
        final int remaining = pendingSteps.size() + activeSteps.size();
        final int done = Math.max(0, totalSteps - remaining);
        return done / (double) totalSteps;
    }

    // Different from TaskImpl: computes availability including cyclic buffered storage reservations.
    private Map<ResourceKey, Long> availableWithReservations(final RootStorage rootStorage) {
        final Map<ResourceKey, Long> available = new HashMap<>();
        for (final ResourceAmount resourceAmount : rootStorage.getAll()) {
            available.put(resourceAmount.resource(), resourceAmount.amount());
        }
        bufferedInternalStorage.getAll().forEach(resource ->
            available.put(resource, available.getOrDefault(resource, 0L) + bufferedInternalStorage.get(resource)));
        return available;
    }

    // Different from TaskImpl: sums future LP step input demand for not-yet-dispatched cyclic steps.
    private long getPendingRequirement(final ResourceKey resource) {
        long amount = 0;
        for (final RecipeApplicationStep step : pendingSteps) {
            for (final var entry : step.recipe().input()) {
                if (RecipeDesanitizer.toSanitizedResourceKey(entry.getKey()).equals(resource)) {
                    amount += entry.getValue() * step.timesApplied();
                }
            }
        }
        return amount;
    }

    // Different from TaskImpl: sums internal storage across all active cyclic step executions.
    private long getActiveInternalStorage(final ResourceKey resource) {
        long amount = 0;
        for (final StepExecution stepExecution : activeSteps.values()) {
            amount += stepExecution.internalStorage.get(resource);
        }
        return amount;
    }

    // Different from TaskImpl: steps pattern sets partitioned per active cyclic step execution.
    private boolean stepActivePatterns(final RootStorage rootStorage,
                                       final ExternalPatternSinkProvider sinkProvider,
                                       final StepBehavior stepBehavior,
                                       final TaskListener listener) {
        boolean changed = false;
        for (final StepExecution stepExecution : activeSteps.values()) {
            final var patternIt = stepExecution.patterns.entrySet().iterator();
            while (patternIt.hasNext()) {
                final var pattern = patternIt.next();
                final PatternStepResult result = stepPattern(
                    rootStorage,
                    sinkProvider,
                    stepBehavior,
                    listener,
                    stepExecution,
                    pattern
                );
                if (result == PatternStepResult.COMPLETED) {
                    patternIt.remove();
                }
                changed |= result != PatternStepResult.IDLE;
            }
        }
        return changed;
    }

    // Different from TaskImpl: prunes completed cyclic step executions and merges their internal storage into the cyclic buffer.
    private boolean pruneCompletedSteps() {
        boolean changed = false;
        final var it = activeSteps.entrySet().iterator();
        while (it.hasNext()) {
            final var entry = it.next();
            final StepExecution stepExecution = entry.getValue();
            if (stepExecution.patterns.isEmpty()) {
                stepExecution.internalStorage.getAll().forEach(resource ->
                    bufferedInternalStorage.add(resource, stepExecution.internalStorage.get(resource)));
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    // Different from TaskImpl: drains active cyclic step storage into the shared buffer before cancellation cleanup.
    private boolean clearActiveSteps() {
        if (activeSteps.isEmpty()) {
            return false;
        }
        for (final StepExecution stepExecution : activeSteps.values()) {
            stepExecution.internalStorage.getAll().forEach(resource ->
                bufferedInternalStorage.add(resource, stepExecution.internalStorage.get(resource)));
        }
        activeSteps.clear();
        return true;
    }

    // Different from TaskImpl: dispatches at most one pending LP step chunk based on current availability.
    private boolean dispatchNextStep(final RootStorage rootStorage) {
        if (!activeSteps.isEmpty() || pendingSteps.isEmpty()) {
            return false;
        }

        final RecipeApplicationStep next = pendingSteps.getFirst();
        final Map<ResourceKey, Long> available = availableWithReservations(rootStorage);
        final long dispatchIterations = maxDispatchableIterations(next, available);
        if (dispatchIterations <= 0) {
            return false;
        }
        final Map<ResourceKey, Long> requirements = stepRequirements(next, dispatchIterations);

        if (!dispatchStepExecution(next, dispatchIterations, requirements, rootStorage)) {
            return false;
        }

        updatePendingStepAfterDispatch(0, next, dispatchIterations);
        return true;
    }

    // Different from TaskImpl: updates pending LP step iterations after partial cyclic dispatch.
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

    // Different from TaskImpl: materializes one LP step chunk into active cyclic execution state.
    private boolean dispatchStepExecution(final RecipeApplicationStep step,
                                          final long dispatchIterations,
                                          final Map<ResourceKey, Long> requirements,
                                          final RootStorage rootStorage) {
        final RecipeApplicationStep dispatchedStep = new RecipeApplicationStep(step.recipe(), dispatchIterations);
        final Pattern pattern = TaskDispatcher.requirePattern(step.recipe(), patternsById);
        final boolean root = pattern.equals(rootPattern);
        final TaskPlan plan = TaskDispatcher.translateLpStepToTaskPlan(
            getResource(),
            -1,
            dispatchedStep,
            patternsById,
            root
        );

        if (!hasProvider.test(pattern)) {
            return false;
        }

        final MutableResourceList seededInternalStorage = MutableResourceListImpl.create();
        final MutableResourceList remainingRequirements = MutableResourceListImpl.create();

        requirements.forEach((resource, amountNeeded) -> {
            final long bufferedAmount = bufferedInternalStorage.get(resource);
            final long consumedFromBuffer = Math.min(bufferedAmount, amountNeeded);
            if (consumedFromBuffer > 0) {
                bufferedInternalStorage.remove(resource, consumedFromBuffer);
                seededInternalStorage.add(resource, consumedFromBuffer);
            }

            final long remaining = amountNeeded - consumedFromBuffer;
            if (remaining > 0) {
                remainingRequirements.add(resource, remaining);
            }
        });

        final Map<Pattern, AbstractTaskPattern> stepPatterns = new LinkedHashMap<>();
        for (final var patternEntry : plan.patterns().entrySet()) {
            stepPatterns.put(
                patternEntry.getKey(),
                TaskImpl.createTaskPatternInternal(patternEntry.getKey(), patternEntry.getValue())
            );
        }

        final int stepIndex = activeSteps.isEmpty()
            ? 0
            : activeSteps.keySet().stream().mapToInt(i -> i).max().orElse(-1) + 1;
        activeSteps.put(stepIndex, new StepExecution(stepPatterns, remainingRequirements.copy(), seededInternalStorage.copy()));
        return true;
    }

    // Different from TaskImpl: computes dispatch limit per LP step from currently available resources.
    private static long maxDispatchableIterations(final RecipeApplicationStep step,
                                                  final Map<ResourceKey, Long> available) {
        long maxIterations = step.timesApplied();
        for (final var entry : step.recipe().input()) {
            final long perIterationAmount = entry.getValue();
            if (perIterationAmount <= 0) {
                continue;
            }
            final long availableAmount = available.getOrDefault(RecipeDesanitizer.toSanitizedResourceKey(entry.getKey()), 0L);
            maxIterations = Math.min(maxIterations, availableAmount / perIterationAmount);
            if (maxIterations <= 0) {
                return 0;
            }
        }
        return Math.max(0, maxIterations);
    }

    // Different from TaskImpl: computes per-dispatch input requirements for a cyclic LP step chunk.
    private static Map<ResourceKey, Long> stepRequirements(final RecipeApplicationStep step,
                                                           final long iterations) {
        final Map<ResourceKey, Long> requirements = new HashMap<>();
        for (final var entry : step.recipe().input()) {
            requirements.merge(RecipeDesanitizer.toSanitizedResourceKey(entry.getKey()), entry.getValue() * iterations, Long::sum);
        }
        return requirements;
    }

    // Different from TaskImpl: holds per-dispatched-step execution state.
    private record StepExecution(Map<Pattern, AbstractTaskPattern> patterns,
                                 MutableResourceList initialRequirements,
                                 MutableResourceList internalStorage) {
    }
}
