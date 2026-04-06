package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpDispatcherHelper;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpPlanningHelper;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpPreviewCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpStepPlan;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpStepPlanCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpTaskDispatcher;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
        return CompletableFuture.supplyAsync(() -> {
            final RootStorage rootStorage = state.getRootStorageProvider().get();
            final Collection<Pattern> relevantPatterns =
                LpPlanningHelper.collectRelevantPatternsForLp(resource, rootStorage, state.getPatternRepository());
            return Optional.of(LpPreviewCalculator.calculatePreview(
                relevantPatterns,
                rootStorage,
                resource,
                amount,
                cancellationToken
            ));
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
        final Collection<Pattern> relevantPatterns =
            LpPlanningHelper.collectRelevantPatternsForLp(resource, rootStorage, state.getPatternRepository());
        return LpStepPlanCalculator.calculateSteps(
            relevantPatterns,
            LOGGER,
            rootStorage,
            resource,
            amount,
            cancellationToken
        ).flatMap(steps -> addLpDispatcherTask(resource, amount, actor, steps, notify));
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
        final Collection<Pattern> relevantPatterns =
            LpPlanningHelper.collectRelevantPatternsForLp(resource, rootStorage, state.getPatternRepository());
        return LpStepPlanCalculator.calculateSteps(
            relevantPatterns,
            LOGGER,
            rootStorage,
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(steps -> addLpDispatcherTask(resource, correctedAmount, actor, steps, false))
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElseGet(() -> ensureTaskForCraftableAmountViaLp(
                relevantPatterns,
                rootStorage,
                resource,
                correctedAmount,
                actor,
                cancellationToken
            ));
    }

    private AutocraftingNetworkComponent.EnsureResult ensureTaskForCraftableAmountViaLp(final Collection<Pattern> relevantPatterns,
                                                                                         final RootStorage rootStorage,
                                                                                         final ResourceKey resource,
                                                                                         final long amount,
                                                                                         final Actor actor,
                                                                                         final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            return AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES;
        }

        final long correctedAmount = findMaxCraftableAmountViaLp(
            relevantPatterns,
            rootStorage,
            resource,
            amount,
            cancellationToken
        );
        if (correctedAmount <= 0) {
            return AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES;
        }

        return LpStepPlanCalculator.calculateSteps(
            state.getPatternRepository().getAll(),
            LOGGER,
            rootStorage,
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(steps -> addLpDispatcherTask(resource, correctedAmount, actor, steps, false))
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElse(AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES);
    }

    private long findMaxCraftableAmountViaLp(final Collection<Pattern> relevantPatterns,
                                             final RootStorage rootStorage,
                                             final ResourceKey resource,
                                             final long amount,
                                             final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            return 0;
        }

        final long lpMax = LpStepPlanCalculator.calculateMaxAmount(
            relevantPatterns,
            LOGGER,
            rootStorage,
            resource,
            amount
        );
        if (lpMax <= 0) {
            return 0;
        }

        if (!LpStepPlanCalculator.hasPatternRecipeCycles(relevantPatterns)) {
            return lpMax;
        }

        long low = 1;
        long high = lpMax;
        long best = 0;

        while (low <= high && !cancellationToken.isCancelled()) {
            final long middle = low + ((high - low) / 2);
            final boolean craftable = LpStepPlanCalculator.calculateSteps(
                relevantPatterns,
                LOGGER,
                rootStorage,
                resource,
                middle,
                cancellationToken
            ).isPresent();
            if (craftable) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return best;
    }

    private Optional<TaskId> addLpDispatcherTask(final ResourceKey resource,
                                                 final long amount,
                                                 final Actor actor,
                                                 final LpStepPlan lpStepPlan,
                                                 final boolean notify) {
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
    }
}