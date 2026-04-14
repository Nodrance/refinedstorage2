package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.CraftingInitializer;
import com.refinedmods.refinedstorage.api.autocrafting.lp.PreviewCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.lp.TaskDispatcher;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.core.CoreValidations;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LinearAutocraftingNetworkComponentImpl extends TraditionalAutocraftingNetworkComponentImpl {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinearAutocraftingNetworkComponentImpl.class);

    LinearAutocraftingNetworkComponentImpl(final AutocraftingNetworkComponentState state) {
        super(state);
    }

    @Override
    public CompletableFuture<Optional<Preview>> getPreview(final ResourceKey resource,
                                                           final long amount,
                                                           final CancellationToken cancellationToken) {
        LOGGER.info("[LAT] Entering getPreview()");
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (cancellationToken.isCancelled()) {
                    return Optional.of(new Preview(PreviewType.CANCELLED, Collections.emptyList(), Collections.emptyList()));
                }
                final RootStorage rootStorage = state.getRootStorageProvider().get();
                CraftingInitializer.SolveAndPreviewResult<Preview> result = CraftingInitializer.solveAndCalculatePreview(
                    rootStorage,
                    state.getPatternRepository(),
                    resource,
                    amount,
                    cancellationToken,
                    (init, path) -> path.map(p -> PreviewCalculator.calculatePreview(p, cancellationToken))
                        .orElse(new Preview(PreviewType.NOT_AVAILABLE, Collections.emptyList(), Collections.emptyList()))
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

    @Override
    public CompletableFuture<Optional<TreePreview>> getTreePreview(final ResourceKey resource,
                                                                   final long amount,
                                                                   final CancellationToken cancellationToken) {
        LOGGER.info("[LAT] Entering getTreePreview()");
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (cancellationToken.isCancelled()) {
                    return Optional.of(new TreePreview(PreviewType.CANCELLED, null, Collections.emptyList()));
                }

                final RootStorage rootStorage = state.getRootStorageProvider().get();
                final CraftingInitializer.SolveAndPreviewResult<TreePreview> result =
                    CraftingInitializer.solveAndCalculatePreview(
                        rootStorage,
                        state.getPatternRepository(),
                        resource,
                        amount,
                        cancellationToken,
                        (init, path) -> CraftingInitializer.calculateTreePreview(
                            resource,
                            amount,
                            rootStorage,
                            init,
                            path
                        )
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

    @Override
    public CompletableFuture<Long> getMaxAmount(final ResourceKey resource,
                                                final CancellationToken cancellationToken) {
        LOGGER.info("[LAT] Entering getMaxAmount()");
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

    @Override
    public Optional<TaskId> startTask(final ResourceKey resource,
                                      final long amount,
                                      final Actor actor,
                                      final boolean notify,
                                      final CancellationToken cancellationToken) {
        LOGGER.info("[LAT] Entering startTask()");
        ResourceAmount.validate(resource, amount);
        final RootStorage rootStorage = state.getRootStorageProvider().get();
        return CraftingInitializer.solveToStepPlan(
            rootStorage,
            state.getPatternRepository(),
            resource,
            amount,
            cancellationToken
        ).flatMap(steps -> TaskDispatcher.addTask(
            resource,
            amount,
            actor,
            steps,
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

    @Override
    public AutocraftingNetworkComponent.EnsureResult ensureTask(final ResourceKey resource,
                                                                final long amount,
                                                                final Actor actor,
                                                                final CancellationToken cancellationToken) {
        LOGGER.info("[LAT] Entering ensureTask()");
        ResourceAmount.validate(resource, amount);
        final long currentlyCrafting = state.getStatuses().stream()
            .filter(status -> status.info().resource().equals(resource))
            .mapToLong(status -> status.info().amount())
            .sum();
        if (currentlyCrafting >= amount) {
            return AutocraftingNetworkComponent.EnsureResult.TASK_ALREADY_RUNNING;
        }

        final RootStorage rootStorage = state.getRootStorageProvider().get();
        final long correctedAmount = amount - currentlyCrafting;
        return CraftingInitializer.solveToStepPlan(
            rootStorage,
            state.getPatternRepository(),
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(steps -> TaskDispatcher.addTask(
                resource,
                correctedAmount,
                actor,
                steps,
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
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElseGet(() -> ensureTaskForCraftableAmountViaLp(
                rootStorage,
                resource,
                correctedAmount,
                actor,
                cancellationToken
            ));
    }

    private AutocraftingNetworkComponent.EnsureResult ensureTaskForCraftableAmountViaLp(
        final RootStorage rootStorage,
        final ResourceKey resource,
        final long amount,
        final Actor actor,
        final CancellationToken cancellationToken
    ) {
        LOGGER.info("[LAT] Entering ensureTaskForCraftableAmountViaLp()");
        if (cancellationToken.isCancelled()) {
            return AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES;
        }

        final long correctedAmount = CraftingInitializer.findMaxCraftableAmount(
            rootStorage,
            state.getPatternRepository(),
            resource,
            amount,
            cancellationToken
        );
        if (correctedAmount <= 0) {
            return AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES;
        }

        return CraftingInitializer.solveToStepPlan(
            rootStorage,
            state.getPatternRepository(),
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(
                steps -> TaskDispatcher.addTask(
                    resource,
                    correctedAmount,
                    actor,
                    steps,
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
                )
            )
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElse(AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES);
    }
}

