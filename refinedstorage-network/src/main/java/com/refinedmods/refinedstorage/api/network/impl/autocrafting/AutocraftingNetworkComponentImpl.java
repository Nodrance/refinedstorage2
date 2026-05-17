package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.lp.CraftingInitializer;
import com.refinedmods.refinedstorage.api.autocrafting.lp.DesanitizedRecipeApplicationPath;
import com.refinedmods.refinedstorage.api.autocrafting.lp.TaskDispatcher;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatusListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.core.CoreValidations;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.ParentContainer;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternListener;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.refinedmods.refinedstorage.api.autocrafting.craftability.IsCraftableCraftingCalculatorListener.binarySearchMaxAmount;
import static com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreviewCraftingCalculatorListener.calculateTree;
import static com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener.calculatePlan;

public class AutocraftingNetworkComponentImpl implements AutocraftingNetworkComponent, ParentContainer {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutocraftingNetworkComponentImpl.class);

    private final Supplier<RootStorage> rootStorageProvider;
    private final ExecutorService executorService;
    private final Set<PatternProvider> providers = new HashSet<>();
    private final Map<Pattern, PatternProvider> providerByPattern = new HashMap<>();
    private final Map<PatternLayout, List<ExternalPatternSink>> sinksByPatternLayout = new HashMap<>();
    private final Map<TaskId, PatternProvider> providerByTaskId = new HashMap<>();
    private final Set<PatternListener> patternListeners = new HashSet<>();
    private final Set<TaskStatusListener> statusListeners = new HashSet<>();
    private final PatternRepositoryImpl patternRepository = new PatternRepositoryImpl();
    private final AutocraftingNetworkComponentState state;

    public AutocraftingNetworkComponentImpl(final Supplier<RootStorage> rootStorageProvider,
                                            final ExecutorService executorService) {
        this.rootStorageProvider = rootStorageProvider;
        this.executorService = executorService;
        this.state = new AutocraftingNetworkComponentState(
            this.rootStorageProvider,
            this.executorService,
            this.providers,
            this.providerByPattern,
            this.sinksByPatternLayout,
            this.providerByTaskId,
            this.patternListeners,
            this.statusListeners,
            this.patternRepository
        );
    }

    @Override
    public void onContainerAdded(final NetworkNodeContainer container) {
        state.onContainerAdded(this, container);
    }

    @Override
    public void onContainerRemoved(final NetworkNodeContainer container) {
        state.onContainerRemoved(this, container);
    }

    @Override
    public Set<ResourceKey> getOutputs() {
        return state.getOutputs();
    }

    @Override
    public boolean contains(final AutocraftingNetworkComponent component) {
        return state.contains(component);
    }

    @Nullable
    @Override
    public PatternProvider getProviderByPattern(final Pattern pattern) {
        return state.getProviderByPattern(pattern);
    }

    @Override
    public CompletableFuture<Optional<Preview>> getPreview(final ResourceKey resource,
                                                           final long amount,
                                                           final CancellationToken cancellationToken) {
        return shouldUseLinearAutocraftingSystem()
            ? getLinearPreview(resource, amount, cancellationToken)
            : getTraditionalPreview(resource, amount, cancellationToken);
    }

    @Override
    public CompletableFuture<Optional<TreePreview>> getTreePreview(final ResourceKey resource,
                                                                   final long amount,
                                                                   final CancellationToken cancellationToken) {
        return shouldUseLinearAutocraftingSystem()
            ? getLinearTreePreview(resource, amount, cancellationToken)
            : getTraditionalTreePreview(resource, amount, cancellationToken);
    }

    @Override
    public CompletableFuture<Long> getMaxAmount(final ResourceKey resource,
                                                final CancellationToken cancellationToken) {
        return shouldUseLinearAutocraftingSystem()
            ? getLinearMaxAmount(resource, cancellationToken)
            : getTraditionalMaxAmount(resource, cancellationToken);
    }

    @Override
    public Optional<TaskId> startTask(final ResourceKey resource,
                                      final long amount,
                                      final Actor actor,
                                      final boolean notify,
                                      final CancellationToken cancellationToken) {
        return shouldUseLinearAutocraftingSystem()
            ? startLinearTask(resource, amount, actor, notify, cancellationToken)
            : startTraditionalTask(resource, amount, actor, notify, cancellationToken);
    }

    @Override
    public EnsureResult ensureTask(final ResourceKey resource,
                                   final long amount,
                                   final Actor actor,
                                   final CancellationToken cancellationToken) {
        return shouldUseLinearAutocraftingSystem()
            ? ensureLinearTask(resource, amount, actor, cancellationToken)
            : ensureTraditionalTask(resource, amount, actor, cancellationToken);
    }

    boolean shouldUseLinearAutocraftingSystem() {
        return AutocraftingModeContext.isUseLinearAutocraftingSystem();
    }

    private CompletableFuture<Optional<Preview>> getTraditionalPreview(final ResourceKey resource,
                                                                       final long amount,
                                                                       final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                final RootStorage rootStorage = state.getRootStorageProvider().get();
                final CraftingCalculator calculator = new CraftingCalculatorImpl(
                    state.getPatternRepository(),
                    rootStorage
                );
                final Preview preview = PreviewCraftingCalculatorListener.calculatePreview(
                    calculator,
                    resource,
                    amount,
                    cancellationToken
                );
                return Optional.of(preview);
            }, state.getExecutorService());
        } catch (final RejectedExecutionException e) {
            return CompletableFuture.completedFuture(Optional.of(new Preview(
                PreviewType.NOT_AVAILABLE,
                Collections.emptyList(),
                Collections.emptyList()
            )));
        }
    }

    private CompletableFuture<Optional<TreePreview>> getTraditionalTreePreview(
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                final RootStorage rootStorage = state.getRootStorageProvider().get();
                final CraftingCalculator calculator = new CraftingCalculatorImpl(
                    state.getPatternRepository(),
                    rootStorage
                );
                final TreePreview tree = calculateTree(calculator, resource, amount, cancellationToken);
                return Optional.of(tree);
            }, state.getExecutorService());
        } catch (final RejectedExecutionException e) {
            return CompletableFuture.completedFuture(Optional.of(new TreePreview(
                PreviewType.NOT_AVAILABLE,
                null,
                Collections.emptyList()
            )));
        }
    }

    private CompletableFuture<Long> getTraditionalMaxAmount(final ResourceKey resource,
                                                            final CancellationToken cancellationToken) {
        CoreValidations.validateNotNull(resource, "Resource cannot be null");
        final RootStorage rootStorage = state.getRootStorageProvider().get();
        final CraftingCalculator calculator = new CraftingCalculatorImpl(state.getPatternRepository(), rootStorage);
        return CompletableFuture.supplyAsync(
            () -> binarySearchMaxAmount(calculator, resource, cancellationToken),
            state.getExecutorService()
        );
    }

    private Optional<TaskId> startTraditionalTask(final ResourceKey resource,
                                                  final long amount,
                                                  final Actor actor,
                                                  final boolean notify,
                                                  final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        final RootStorage rootStorage = state.getRootStorageProvider().get();
        final CraftingCalculator calculator = new CraftingCalculatorImpl(state.getPatternRepository(), rootStorage);
        return calculatePlan(calculator, resource, amount, cancellationToken)
            .map(plan -> state.addTask(resource, amount, actor, plan, notify, LOGGER));
    }

    private EnsureResult ensureTraditionalTask(final ResourceKey resource,
                                               final long amount,
                                               final Actor actor,
                                               final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        final long currentlyCrafting = state.getStatuses().stream()
            .filter(status -> status.info().resource().equals(resource))
            .mapToLong(status -> status.info().amount())
            .sum();
        if (currentlyCrafting >= amount) {
            return EnsureResult.TASK_ALREADY_RUNNING;
        }

        final RootStorage rootStorage = state.getRootStorageProvider().get();
        final long correctedAmount = amount - currentlyCrafting;
        final CraftingCalculator calculator = new CraftingCalculatorImpl(state.getPatternRepository(), rootStorage);
        return calculatePlan(calculator, resource, correctedAmount, cancellationToken)
            .map(plan -> state.addTask(resource, correctedAmount, actor, plan, false, LOGGER))
            .map(taskId -> EnsureResult.TASK_CREATED)
            .orElseGet(() -> ensureTraditionalTaskForCraftableAmount(
                resource,
                actor,
                correctedAmount,
                calculator,
                cancellationToken
            ));
    }

    private EnsureResult ensureTraditionalTaskForCraftableAmount(final ResourceKey resource,
                                                                 final Actor actor,
                                                                 final long amount,
                                                                 final CraftingCalculator calculator,
                                                                 final CancellationToken cancellationToken) {
        final long correctedAmount = Math.min(
            binarySearchMaxAmount(calculator, resource, CancellationToken.NONE),
            amount
        );
        if (correctedAmount <= 0) {
            return EnsureResult.MISSING_RESOURCES;
        }
        return calculatePlan(calculator, resource, correctedAmount, cancellationToken)
            .map(plan -> state.addTask(resource, correctedAmount, actor, plan, false, LOGGER))
            .map(taskId -> EnsureResult.TASK_CREATED)
            .orElse(EnsureResult.MISSING_RESOURCES);
    }

    private CompletableFuture<Optional<Preview>> getLinearPreview(final ResourceKey resource,
                                                                  final long amount,
                                                                  final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (cancellationToken.isCancelled()) {
                    return Optional.of(new Preview(
                        PreviewType.CANCELLED,
                        Collections.emptyList(),
                        Collections.emptyList()
                    ));
                }
                final RootStorage rootStorage = state.getRootStorageProvider().get();
                final CraftingInitializer.SolveAndPreviewResult result = CraftingInitializer.solveAndCalculatePreview(
                    rootStorage,
                    state.getPatternRepository(),
                    resource,
                    amount,
                    cancellationToken
                );
                return Optional.of(result.previewResult());
            }, state.getExecutorService());
        } catch (final RejectedExecutionException e) {
            return CompletableFuture.completedFuture(Optional.of(new Preview(
                PreviewType.NOT_AVAILABLE,
                Collections.emptyList(),
                Collections.emptyList()
            )));
        }
    }

    private CompletableFuture<Optional<TreePreview>> getLinearTreePreview(final ResourceKey resource,
                                                                          final long amount,
                                                                          final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (cancellationToken.isCancelled()) {
                    return Optional.of(new TreePreview(PreviewType.CANCELLED, null, Collections.emptyList()));
                }
                final RootStorage rootStorage = state.getRootStorageProvider().get();
                final CraftingInitializer.SolveAndTreePreviewResult result =
                    CraftingInitializer.solveAndCalculateTreePreview(
                        rootStorage,
                        state.getPatternRepository(),
                        resource,
                        amount,
                        cancellationToken
                    );
                return Optional.of(result.previewResult());
            }, state.getExecutorService());
        } catch (final RejectedExecutionException e) {
            return CompletableFuture.completedFuture(Optional.of(new TreePreview(
                PreviewType.NOT_AVAILABLE,
                null,
                Collections.emptyList()
            )));
        }
    }

    private CompletableFuture<Long> getLinearMaxAmount(final ResourceKey resource,
                                                       final CancellationToken cancellationToken) {
        CoreValidations.validateNotNull(resource, "Resource cannot be null");
        final RootStorage rootStorage = state.getRootStorageProvider().get();
        return CompletableFuture.supplyAsync(
            () -> CraftingInitializer.findMaxCraftableAmount(
                rootStorage,
                state.getPatternRepository(),
                resource,
                Long.MAX_VALUE,
                cancellationToken
            ),
            state.getExecutorService()
        );
    }

    private Optional<TaskId> startLinearTask(final ResourceKey resource,
                                             final long amount,
                                             final Actor actor,
                                             final boolean notify,
                                             final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        final RootStorage rootStorage = state.getRootStorageProvider().get();
        return solveLinearCraftablePath(
            rootStorage,
            state.getPatternRepository(),
            resource,
            amount,
            cancellationToken
        ).flatMap(path -> TaskDispatcher.addTask(
            resource,
            amount,
            actor,
            path,
            state.getPatternRepository().getAll(),
            notify,
            (taskActor, plan, shouldNotify) -> state.addSingleStepTask(taskActor, plan, shouldNotify, LOGGER),
            (pattern, task) -> {
                final var provider = state.getProviderByPatternMap().get(pattern);
                if (provider == null) {
                    return false;
                }
                provider.addTask(task);
                return true;
            },
            pattern -> state.getProviderByPatternMap().containsKey(pattern)
        ));
    }

    private EnsureResult ensureLinearTask(final ResourceKey resource,
                                          final long amount,
                                          final Actor actor,
                                          final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        final long currentlyCrafting = state.getStatuses().stream()
            .filter(status -> status.info().resource().equals(resource))
            .mapToLong(status -> status.info().amount())
            .sum();
        if (currentlyCrafting >= amount) {
            return EnsureResult.TASK_ALREADY_RUNNING;
        }

        final RootStorage rootStorage = state.getRootStorageProvider().get();
        final long correctedAmount = amount - currentlyCrafting;
        return solveLinearCraftablePath(
            rootStorage,
            state.getPatternRepository(),
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(path -> TaskDispatcher.addTask(
                resource,
                correctedAmount,
                actor,
                path,
                state.getPatternRepository().getAll(),
                false,
                (taskActor, plan, shouldNotify) -> state.addSingleStepTask(taskActor, plan, shouldNotify, LOGGER),
                (pattern, task) -> {
                    final var provider = state.getProviderByPatternMap().get(pattern);
                    if (provider == null) {
                        return false;
                    }
                    provider.addTask(task);
                    return true;
                },
                pattern -> state.getProviderByPatternMap().containsKey(pattern)
            ))
            .map(taskId -> EnsureResult.TASK_CREATED)
            .orElseGet(() -> ensureLinearTaskForCraftableAmount(
                rootStorage,
                resource,
                correctedAmount,
                actor,
                cancellationToken
            ));
    }

    private EnsureResult ensureLinearTaskForCraftableAmount(final RootStorage rootStorage,
                                                            final ResourceKey resource,
                                                            final long amount,
                                                            final Actor actor,
                                                            final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            return EnsureResult.MISSING_RESOURCES;
        }

        final long correctedAmount = CraftingInitializer.findMaxCraftableAmount(
            rootStorage,
            state.getPatternRepository(),
            resource,
            amount,
            cancellationToken
        );
        if (correctedAmount <= 0) {
            return EnsureResult.MISSING_RESOURCES;
        }

        return solveLinearCraftablePath(
            rootStorage,
            state.getPatternRepository(),
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(path -> TaskDispatcher.addTask(
                resource,
                correctedAmount,
                actor,
                path,
                state.getPatternRepository().getAll(),
                false,
                (taskActor, plan, shouldNotify) -> state.addSingleStepTask(taskActor, plan, shouldNotify, LOGGER),
                (pattern, task) -> {
                    final var provider = state.getProviderByPatternMap().get(pattern);
                    if (provider == null) {
                        return false;
                    }
                    provider.addTask(task);
                    return true;
                },
                pattern -> state.getProviderByPatternMap().containsKey(pattern)
            ))
            .map(taskId -> EnsureResult.TASK_CREATED)
            .orElse(EnsureResult.MISSING_RESOURCES);
    }

    private static Optional<DesanitizedRecipeApplicationPath> solveLinearCraftablePath(
        final RootStorage rootStorage,
        final PatternRepositoryImpl patternRepository,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        try {
            return CraftingInitializer.solveToStepPlan(
                rootStorage,
                patternRepository,
                resource,
                amount,
                cancellationToken
            ).filter(path -> path.applicationSet().missingResources().isEmpty());
        } catch (final CancellationException e) {
            return Optional.empty();
        }
    }

    @Override
    public void addListener(final PatternListener listener) {
        state.addListener(listener);
    }

    @Override
    public void addListener(final TaskStatusListener listener) {
        state.addListener(listener);
    }

    @Override
    public void removeListener(final PatternListener listener) {
        state.removeListener(listener);
    }

    @Override
    public void removeListener(final TaskStatusListener listener) {
        state.removeListener(listener);
    }

    @Override
    public Set<Pattern> getPatterns() {
        return state.getPatterns();
    }

    @Override
    public List<Pattern> getPatternsByOutput(final ResourceKey output) {
        return state.getPatternsByOutput(output);
    }

    @Override
    public List<TaskStatus> getStatuses() {
        return state.getStatuses();
    }

    @Override
    public void cancel(final TaskId taskId) {
        state.cancel(taskId);
    }

    @Override
    public void cancelAll() {
        state.cancelAll();
    }

    @Override
    public void add(final PatternProvider provider, final Pattern pattern, final int priority) {
        state.add(provider, pattern, priority);
    }

    @Override
    public void remove(final PatternProvider provider, final Pattern pattern) {
        state.remove(provider, pattern);
    }

    @Override
    public void update(final Pattern pattern, final int priority) {
        state.update(pattern, priority);
    }

    @Override
    public void taskAdded(final PatternProvider provider, final Task task) {
        state.taskAdded(provider, task);
    }

    @Override
    public void taskRemoved(final Task task) {
        state.taskRemoved(task);
    }

    @Override
    public void taskCompleted(final Task task) {
        state.taskCompleted(task);
    }

    @Override
    public void taskChanged(final Task task) {
        state.taskChanged(task);
    }

    @Override
    public List<ExternalPatternSink> getSinksByPatternLayout(final PatternLayout patternLayout) {
        return state.getSinksByPatternLayout(patternLayout);
    }
}
