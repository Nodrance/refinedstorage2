package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.CraftingInitializer;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpDispatcherHelper;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpStepPlan;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpTaskDispatcher;
import com.refinedmods.refinedstorage.api.autocrafting.lp.PreviewBuilder;
import com.refinedmods.refinedstorage.api.autocrafting.lp.RecipeApplicationPath;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.core.CoreValidations;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
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
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                final RootStorage rootStorage = state.getRootStorageProvider().get();
                final Optional<RecipeApplicationPath> recipeApplicationPath = CraftingInitializer.solve(
                    rootStorage,
                    state.getPatternRepository(),
                    resource,
                    amount,
                    cancellationToken
                );
                return Optional.of(PreviewBuilder.buildPreview(recipeApplicationPath, cancellationToken));
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
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                final RootStorage rootStorage = state.getRootStorageProvider().get();
                final Optional<RecipeApplicationPath> recipeApplicationPath = CraftingInitializer.solve(
                    rootStorage,
                    state.getPatternRepository(),
                    resource,
                    amount,
                    cancellationToken
                );
                return Optional.of(PreviewBuilder.buildTreePreview(recipeApplicationPath, cancellationToken));
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
        CoreValidations.validateNotNull(resource, "Resource cannot be null");
        return CompletableFuture.supplyAsync(() -> {
            final RootStorage rootStorage = state.getRootStorageProvider().get();
            return CraftingInitializer.findMaxCraftableAmount(
                rootStorage,
                state.getPatternRepository(),
                resource,
                Long.MAX_VALUE,
                cancellationToken
            );
        }, state.getExecutorService());
    }

    @Override
    public Optional<TaskId> startTask(final ResourceKey resource,
                                      final long amount,
                                      final Actor actor,
                                      final boolean notify,
                                      final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        final RootStorage rootStorage = state.getRootStorageProvider().get();
        return CraftingInitializer.solveToStepPlan(
            rootStorage,
            state.getPatternRepository(),
            resource,
            amount,
            cancellationToken
        ).flatMap(lpStepPlan -> {
            if (lpStepPlan.steps().size() == 1) {
                final TaskPlan singleStepPlan = LpDispatcherHelper.toSingleStepPlan(
                    resource,
                    amount,
                    lpStepPlan.steps().getFirst(),
                    true
                );
                return Optional.of(state.addSingleStepTask(actor, singleStepPlan, notify, LOGGER));
            }

            final Pattern rootPattern = LpDispatcherHelper.findRootPattern(resource, lpStepPlan.steps());
            if (rootPattern == null) {
                return Optional.empty();
            }

            final PatternProvider provider = state.getProviderByPatternMap().get(rootPattern);
            if (provider == null) {
                return Optional.empty();
            }

            final Task dispatcher = new LpTaskDispatcher(
                resource,
                amount,
                actor,
                notify,
                lpStepPlan,
                rootPattern,
                pattern -> state.getProviderByPatternMap().containsKey(pattern)
            );
            provider.addTask(dispatcher);
            return Optional.of(dispatcher.getId());
        });
    }

    @Override
    public AutocraftingNetworkComponent.EnsureResult ensureTask(final ResourceKey resource,
                                                                final long amount,
                                                                final Actor actor,
                                                                final CancellationToken cancellationToken) {
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
            .flatMap(lpStepPlan -> {
                if (lpStepPlan.steps().size() == 1) {
                    final TaskPlan singleStepPlan = LpDispatcherHelper.toSingleStepPlan(
                        resource,
                        correctedAmount,
                        lpStepPlan.steps().getFirst(),
                        true
                    );
                    return Optional.of(state.addSingleStepTask(actor, singleStepPlan, false, LOGGER));
                }

                final Pattern rootPattern = LpDispatcherHelper.findRootPattern(resource, lpStepPlan.steps());
                if (rootPattern == null) {
                    return Optional.empty();
                }

                final PatternProvider provider = state.getProviderByPatternMap().get(rootPattern);
                if (provider == null) {
                    return Optional.empty();
                }

                final Task dispatcher = new LpTaskDispatcher(
                    resource,
                    correctedAmount,
                    actor,
                    false,
                    lpStepPlan,
                    rootPattern,
                    pattern -> state.getProviderByPatternMap().containsKey(pattern)
                );
                provider.addTask(dispatcher);
                return Optional.of(dispatcher.getId());
            })
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElseGet(() -> ensureTaskForCraftableAmount(
                rootStorage,
                resource,
                correctedAmount,
                actor,
                cancellationToken
            ));
    }

    private AutocraftingNetworkComponent.EnsureResult ensureTaskForCraftableAmount(
        final RootStorage rootStorage,
        final ResourceKey resource,
        final long amount,
        final Actor actor,
        final CancellationToken cancellationToken
    ) {
        final long correctedAmount = CraftingInitializer.findMaxCraftableAmount(
            rootStorage,
            state.getPatternRepository(),
            resource,
            amount,
            cancellationToken
        );
        if (correctedAmount <= 0L) {
            return AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES;
        }

        return CraftingInitializer.solveToStepPlan(
            rootStorage,
            state.getPatternRepository(),
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(lpStepPlan -> {
                if (lpStepPlan.steps().size() == 1) {
                    final TaskPlan singleStepPlan = LpDispatcherHelper.toSingleStepPlan(
                        resource,
                        correctedAmount,
                        lpStepPlan.steps().getFirst(),
                        true
                    );
                    return Optional.of(state.addSingleStepTask(actor, singleStepPlan, false, LOGGER));
                }

                final Pattern rootPattern = LpDispatcherHelper.findRootPattern(resource, lpStepPlan.steps());
                if (rootPattern == null) {
                    return Optional.empty();
                }

                final PatternProvider provider = state.getProviderByPatternMap().get(rootPattern);
                if (provider == null) {
                    return Optional.empty();
                }

                final Task dispatcher = new LpTaskDispatcher(
                    resource,
                    correctedAmount,
                    actor,
                    false,
                    lpStepPlan,
                    rootPattern,
                    pattern -> state.getProviderByPatternMap().containsKey(pattern)
                );
                provider.addTask(dispatcher);
                return Optional.of(dispatcher.getId());
            })
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElse(AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES);
    }
}
