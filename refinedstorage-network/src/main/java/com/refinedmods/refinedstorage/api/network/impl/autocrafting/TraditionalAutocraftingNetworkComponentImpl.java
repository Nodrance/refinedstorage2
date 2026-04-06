package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewCraftingCalculatorListener;
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

import static com.refinedmods.refinedstorage.api.autocrafting.craftability.IsCraftableCraftingCalculatorListener.binarySearchMaxAmount;
import static com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreviewCraftingCalculatorListener.calculateTree;
import static com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener.calculatePlan;

class TraditionalAutocraftingNetworkComponentImpl {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraditionalAutocraftingNetworkComponentImpl.class);

    protected final AutocraftingNetworkComponentState state;

    TraditionalAutocraftingNetworkComponentImpl(final AutocraftingNetworkComponentState state) {
        this.state = state;
    }

    public CompletableFuture<Optional<Preview>> getPreview(final ResourceKey resource,
                                                           final long amount,
                                                           final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                final RootStorage rootStorage = state.getRootStorageProvider().get();
                final CraftingCalculator calculator = new CraftingCalculatorImpl(state.getPatternRepository(), rootStorage);
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

    public CompletableFuture<Optional<TreePreview>> getTreePreview(final ResourceKey resource,
                                                                   final long amount,
                                                                   final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        try {
            return CompletableFuture.supplyAsync(() -> {
                final RootStorage rootStorage = state.getRootStorageProvider().get();
                final CraftingCalculator calculator = new CraftingCalculatorImpl(state.getPatternRepository(), rootStorage);
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

    public CompletableFuture<Long> getMaxAmount(final ResourceKey resource,
                                                final CancellationToken cancellationToken) {
        CoreValidations.validateNotNull(resource, "Resource cannot be null");
        final RootStorage rootStorage = state.getRootStorageProvider().get();
        final CraftingCalculator calculator = new CraftingCalculatorImpl(state.getPatternRepository(), rootStorage);
        return CompletableFuture.supplyAsync(
            () -> binarySearchMaxAmount(calculator, resource, cancellationToken),
            state.getExecutorService()
        );
    }

    public Optional<TaskId> startTask(final ResourceKey resource,
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
        final CraftingCalculator calculator = new CraftingCalculatorImpl(state.getPatternRepository(), rootStorage);
        return calculatePlan(calculator, resource, correctedAmount, cancellationToken)
            .map(plan -> state.addTask(resource, correctedAmount, actor, plan, false, LOGGER))
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElseGet(() -> ensureTaskForCraftableAmount(resource, actor, correctedAmount, calculator, cancellationToken));
    }

    private AutocraftingNetworkComponent.EnsureResult ensureTaskForCraftableAmount(final ResourceKey resource,
                                                                                   final Actor actor,
                                                                                   final long amount,
                                                                                   final CraftingCalculator calculator,
                                                                                   final CancellationToken cancellationToken) {
        final long correctedAmount = Math.min(
            binarySearchMaxAmount(calculator, resource, CancellationToken.NONE),
            amount
        );
        if (correctedAmount <= 0) {
            return AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES;
        }
        return calculatePlan(calculator, resource, correctedAmount, cancellationToken)
            .map(plan -> state.addTask(resource, correctedAmount, actor, plan, false, LOGGER))
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElse(AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES);
    }
}