package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.CraftingSolver;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.LinearSolver;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.MultiResourceKey;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.ResourcePool;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.SanitizedRecipe;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.RecipeDesanitizer;
import com.refinedmods.refinedstorage.api.autocrafting.lp.preview.PreviewCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.DesanitizedRecipeApplicationPath;
import com.refinedmods.refinedstorage.api.autocrafting.lp.sanitization.CraftingInitializer;
import com.refinedmods.refinedstorage.api.autocrafting.lp.sanitization.RecipeSanitizer;

// Orchestrates crafting tasks. Contains the main entry points for everything.
public final class CraftingOrchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingOrchestrator.class);

    private CraftingOrchestrator() {
    }

    public static Optional<DesanitizedRecipeApplicationPath> solve(
        final RootStorage rootStorage,
        final PatternRepository patternRepository,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        LOGGER.debug("[LPT] Entering solve(RootStorage, PatternRepository, ResourceKey, long, CancellationToken)");
        try {
            final Initialization initialization = CraftingInitializer.initialize(
                rootStorage,
                patternRepository,
                resource,
                amount,
                cancellationToken
            );
            // LOGGER.debug(
            //     "[LP] Initialization for solve: {} sanitizedRecipes, {} relevantStartingResources, {} target, "
            //         + "{} relevantResources, {} relevantPatterns, {} trimmedPatterns",
            //     initialization.sanitizedRecipes().size(),
            //     initialization.relevantStartingResources(),
            //     initialization.target(),
            //     initialization.relevantResources().size(),
            //     initialization.relevantPatterns().size(),
            //     initialization.trimmedPatterns().size()
            // );
            final Optional<DesanitizedRecipeApplicationPath> result = solve(initialization, cancellationToken);
            // LOGGER.debug("[LP] Solve result: {}", result);
            return result;
        } catch (final CraftingInitializer.LpInputOverflowException e) {
            LOGGER.debug("[LP] solve(...) input overflow detected", e);
            return Optional.empty();
        } catch (final java.util.concurrent.CancellationException e) {
            LOGGER.debug("[LP] solve(...) cancelled before path construction");
            return Optional.empty();
        }
    }

    public static Optional<DesanitizedRecipeApplicationPath> solve(
        final Initialization initialization,
        final CancellationToken cancellationToken
    ) {
        LOGGER.debug("[LPT] Entering solve(Initialization, CancellationToken)");
        try {
            Objects.requireNonNull(initialization, "initialization cannot be null");
            Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
            throwIfCancelled(cancellationToken);

            final CancellationToken loopSnippingCancellationToken = createLoopSnippingCancellationToken(
                cancellationToken
            );

            return new CraftingSolver(cancellationToken, loopSnippingCancellationToken).solve(
                initialization.sanitizedRecipes(),
                initialization.relevantStartingResources(),
                initialization.target()
            ).map(path -> RecipeDesanitizer.decodeRecipeApplicationPathToDesanitized(
                path,
                initialization.sanitizedStartingResources(),
                cancellationToken
            ));
        } catch (final java.util.concurrent.CancellationException e) {
            LOGGER.debug("[LP] solve(initialization, ...) cancelled before path construction");
            return Optional.empty();
        }
    }

    public static Optional<DesanitizedRecipeApplicationPath> solveToStepPlan(
        final RootStorage rootStorage,
        final PatternRepository patternRepository,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        LOGGER.debug(
            "[LPT] Entering solveToStepPlan("
                + "RootStorage, PatternRepository, ResourceKey, long, CancellationToken)"
        );
        try {
            final Initialization initialization = CraftingInitializer.initialize(
                rootStorage,
                patternRepository,
                resource,
                amount,
                cancellationToken
            );
            return solve(initialization, cancellationToken);
        } catch (final CraftingInitializer.LpInputOverflowException e) {
            LOGGER.debug("[LP] solveToStepPlan(...) input overflow detected", e);
            return Optional.empty();
        } catch (final java.util.concurrent.CancellationException e) {
            LOGGER.debug("[LP] solveToStepPlan(...) cancelled before path construction");
            return Optional.empty();
        }
    }

    public static Optional<DesanitizedRecipeApplicationPath> solveToStepPlan(
        final Initialization initialization,
        final CancellationToken cancellationToken
    ) {
        LOGGER.debug("[LPT] Entering solveToStepPlan(Initialization, CancellationToken)");
        try {
            Objects.requireNonNull(initialization, "initialization cannot be null");
            Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
            throwIfCancelled(cancellationToken);

            return solve(initialization, cancellationToken);
        } catch (final java.util.concurrent.CancellationException e) {
            LOGGER.debug("[LP] solveToStepPlan(initialization, ...) cancelled before path construction");
            return Optional.empty();
        }
    }

    public static long findMaxCraftableAmount(
        final RootStorage rootStorage,
        final PatternRepository patternRepository,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        LOGGER.debug("[LPT] Entering findMaxCraftableAmount()");
        if (cancellationToken.isCancelled()) {
            return 0L;
        }

        final Initialization maxCalculationInitialization;
        try {
            maxCalculationInitialization = CraftingInitializer.initialize(
                rootStorage,
                patternRepository,
                resource,
                1L,
                cancellationToken
            );
        } catch (final CraftingInitializer.LpInputOverflowException e) {
            LOGGER.debug("[LP] findMaxCraftableAmount(...) input overflow detected", e);
            return 0L;
        }
        if (cancellationToken.isCancelled()) {
            return 0L;
        }
        final MultiResourceKey targetResource = new MultiResourceKey(List.of(resource));

        final LinearSolver.Result result = new LinearSolver(
            maxCalculationInitialization.sanitizedRecipes(),
            maxCalculationInitialization.relevantResources(),
            maxCalculationInitialization.relevantStartingResources(),
            ResourcePool.empty(),
            Set.of(),
            Set.of(),
            LinearSolver.Options.defaults(),
            cancellationToken
        ).maximize(targetResource);
        if (cancellationToken.isCancelled() || result == null) {
            return 0L;
        }

        final long startingAmount = maxCalculationInitialization.relevantStartingResources().getAmount(targetResource);
        final long lpUpperBound = Math.max(
            0L,
            result.finalInventoryValues().getAmount(targetResource) - startingAmount
        );
        if (lpUpperBound <= 0L) {
            return 0L;
        }

        final long n = Math.min(amount, lpUpperBound);
        final boolean nPlusOneCraftable = n < amount
            && n < Long.MAX_VALUE
            && canCraftWithoutMissingResources(maxCalculationInitialization, targetResource, n + 1L, cancellationToken);
        if (cancellationToken.isCancelled()) {
            return 0L;
        }

        final boolean nCraftable = canCraftWithoutMissingResources(
            maxCalculationInitialization,
            targetResource,
            n,
            cancellationToken
        );
        if (cancellationToken.isCancelled()) {
            return 0L;
        }

        if (nPlusOneCraftable) {
            long best = n + 1L;
            if (best >= amount) {
                return amount;
            }

            long high = best;
            while (high < amount && !cancellationToken.isCancelled()) {
                final long candidate = high > Long.MAX_VALUE / 2 ? amount : Math.min(amount, high * 2L);
                if (candidate <= high) {
                    break;
                }
                if (canCraftWithoutMissingResources(
                    maxCalculationInitialization,
                    targetResource,
                    candidate,
                    cancellationToken
                )) {
                    best = candidate;
                    high = candidate;
                    if (best >= amount) {
                        return amount;
                    }
                } else {
                    final long refined = binarySearchMaxCraftableAmount(
                        maxCalculationInitialization,
                        targetResource,
                        best + 1L,
                        candidate - 1L,
                        cancellationToken
                    );
                    return Math.max(best, refined);
                }
            }
            return best;
        }

        if (nCraftable) {
            return n;
        }

        if (n <= 1L) {
            return 0L;
        }

        return binarySearchMaxCraftableAmount(
            maxCalculationInitialization,
            targetResource,
            1L,
            n - 1L,
            cancellationToken
        );
    }

    private static long binarySearchMaxCraftableAmount(
        final Initialization initialization,
        final MultiResourceKey targetResource,
        final long low,
        final long high,
        final CancellationToken cancellationToken
    ) {
        long left = low;
        long right = high;
        long best = 0L;
        while (left <= right && !cancellationToken.isCancelled()) {
            final long middle = left + ((right - left) / 2L);
            if (canCraftWithoutMissingResources(initialization, targetResource, middle, cancellationToken)) {
                best = middle;
                left = middle + 1L;
            } else {
                right = middle - 1L;
            }
        }
        return best;
    }

    private static boolean canCraftWithoutMissingResources(
        final Initialization initialization,
        final MultiResourceKey targetResource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        if (cancellationToken.isCancelled()) {
            return false;
        }
        final ResourcePool target = ResourcePool.empty();
        target.setAmount(
            targetResource,
            initialization.relevantStartingResources().getAmount(targetResource) + amount
        );
        final CancellationToken loopSnippingCancellationToken = createLoopSnippingCancellationToken(
            cancellationToken
        );
        return new CraftingSolver(cancellationToken, loopSnippingCancellationToken).canCraftWithoutMissingResources(
            initialization.sanitizedRecipes(),
            initialization.relevantStartingResources(),
            target
        );
    }

    private static CancellationToken createLoopSnippingCancellationToken(
        final CancellationToken everythingCancellationToken
    ) {
        final long everythingRemaining = everythingCancellationToken.timeRemainingMillis();
        final long loopBudgetMillis = everythingRemaining == Long.MAX_VALUE
            ? Long.MAX_VALUE
            : Math.max(0L, everythingRemaining - 2000L);

        return new CancellationToken() {
            private volatile boolean cancelled;
            private final long deadlineNanos = loopBudgetMillis == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(loopBudgetMillis);

            @Override
            public boolean isCancelled() {
                return cancelled
                    || everythingCancellationToken.isCancelled()
                    || (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos);
            }

            @Override
            public void cancel() {
                cancelled = true;
            }

            @Override
            public long timeRemainingMillis() {
                if (cancelled || everythingCancellationToken.isCancelled()) {
                    return 0L;
                }

                final long parentRemaining = everythingCancellationToken.timeRemainingMillis();
                final long localRemaining = deadlineNanos == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, deadlineNanos - System.nanoTime()));

                if (parentRemaining == Long.MAX_VALUE) {
                    return localRemaining;
                }
                if (localRemaining == Long.MAX_VALUE) {
                    return Math.max(0L, parentRemaining);
                }
                return Math.max(0L, Math.min(parentRemaining, localRemaining));
            }
        };
    }

    public static SolveAndPreviewResult solveAndCalculatePreview(
        final RootStorage rootStorage,
        final PatternRepository patternRepository,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        LOGGER.debug("[LPT] Entering solveAndCalculatePreview()");
        try {
            final Initialization initialization = CraftingInitializer.initialize(
                rootStorage,
                patternRepository,
                resource,
                amount,
                cancellationToken
            );
            // LOGGER.debug(
            //     "[LP] Initialization for solveAndCalculatePreview: {} sanitizedRecipes, "
            //         + "{} relevantStartingResources, {} target, {} relevantResources, "
            //         + "{} relevantPatterns, {} trimmedPatterns",
            //     initialization.sanitizedRecipes().size(), 
            //     initialization.relevantStartingResources(), 
            //     initialization.target(), 
            //     initialization.relevantResources().size(), 
            //     initialization.relevantPatterns().size(), 
            //     initialization.trimmedPatterns().size()
            // );
            final Optional<DesanitizedRecipeApplicationPath> recipeApplicationPath = solve(initialization, cancellationToken);
            // LOGGER.debug("[LP] Solve result for preview: {}", recipeApplicationPath);
            final Preview previewResult = recipeApplicationPath
                .map(path -> PreviewCalculator.calculatePreview(path, Set.of(resource), cancellationToken))
                .orElse(new Preview(PreviewType.NOT_AVAILABLE, Collections.emptyList(), Collections.emptyList()));
            // LOGGER.debug("[LP] Preview result: {}", previewResult);
            return new SolveAndPreviewResult(initialization, recipeApplicationPath, previewResult);
        } catch (final CraftingInitializer.LpInputOverflowException e) {
            LOGGER.debug("[LP] solveAndCalculatePreview(...) input overflow detected", e);
            return new SolveAndPreviewResult(
                emptyInitialization(),
                Optional.empty(),
                new Preview(PreviewType.OVERFLOW, Collections.emptyList(), Collections.emptyList())
            );
        } catch (final java.util.concurrent.CancellationException e) {
            LOGGER.debug("[LP] solveAndCalculatePreview(...) cancelled");
            return new SolveAndPreviewResult(
                emptyInitialization(),
                Optional.empty(),
                new Preview(PreviewType.CANCELLED, Collections.emptyList(), Collections.emptyList())
            );
        }
    }

    public static SolveAndTreePreviewResult solveAndCalculateTreePreview(
        final RootStorage rootStorage,
        final PatternRepository patternRepository,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        LOGGER.debug("[LPT] Entering solveAndCalculateTreePreview()");
        try {
            final Initialization initialization = CraftingInitializer.initialize(
                rootStorage,
                patternRepository,
                resource,
                amount,
                cancellationToken
            );
            LOGGER.debug(
                "[LP] Initialization for solveAndCalculateTreePreview: {} sanitizedRecipes, "
                    + "{} relevantStartingResources, {} target, {} relevantResources, "
                    + "{} relevantPatterns, {} trimmedPatterns",
                initialization.sanitizedRecipes().size(),
                initialization.relevantStartingResources(),
                initialization.target(),
                initialization.relevantResources().size(),
                initialization.relevantPatterns().size(),
                initialization.trimmedPatterns().size()
            );
            final Optional<DesanitizedRecipeApplicationPath> recipeApplicationPath = solve(initialization, cancellationToken);
            // LOGGER.debug("[LP] Solve result for tree preview: {}", recipeApplicationPath);
            final TreePreview previewResult = PreviewCalculator.calculateTreePreview(
                resource,
                amount,
                rootStorage,
                initialization,
                recipeApplicationPath
            );
            // LOGGER.debug("[LP] Tree preview result: {}", previewResult);
            return new SolveAndTreePreviewResult(initialization, recipeApplicationPath, previewResult);
        } catch (final CraftingInitializer.LpInputOverflowException e) {
            LOGGER.debug("[LP] solveAndCalculateTreePreview(...) input overflow detected", e);
            return new SolveAndTreePreviewResult(
                emptyInitialization(),
                Optional.empty(),
                new TreePreview(PreviewType.OVERFLOW, null, Collections.emptyList())
            );
        } catch (final java.util.concurrent.CancellationException e) {
            LOGGER.debug("[LP] solveAndCalculateTreePreview(...) cancelled");
            return new SolveAndTreePreviewResult(
                emptyInitialization(),
                Optional.empty(),
                new TreePreview(PreviewType.CANCELLED, null, Collections.emptyList())
            );
        }
    }

    public static TreePreview calculateTreePreview(
        final ResourceKey resource,
        final long amount,
        final RootStorage rootStorage,
        final Initialization initialization,
        final Optional<DesanitizedRecipeApplicationPath> recipeApplicationPath
    ) {
        return PreviewCalculator.calculateTreePreview(resource, amount, rootStorage, initialization, recipeApplicationPath);
    }

    private static void throwIfCancelled(final CancellationToken cancellationToken) {
        LOGGER.debug("[LPT] Entering throwIfCancelled()");
        if (cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("LP crafting initializer cancelled");
        }
    }

    private static Initialization emptyInitialization() {
        return new Initialization(
            List.of(),
            ResourcePool.empty(),
            ResourcePool.empty(),
            Map.of(),
            Set.of(),
            List.of(),
            List.of()
        );
    }

    public record Initialization(
        List<SanitizedRecipe> sanitizedRecipes,
        ResourcePool relevantStartingResources,
        ResourcePool target,
        Map<ResourceKey, Long> sanitizedStartingResources,
        Set<MultiResourceKey> relevantResources,
        List<Pattern> relevantPatterns,
        List<Pattern> trimmedPatterns
    ) {
        public Initialization {
            Objects.requireNonNull(sanitizedRecipes, "sanitizedRecipes cannot be null");
            Objects.requireNonNull(relevantStartingResources, "relevantStartingResources cannot be null");
            Objects.requireNonNull(target, "target cannot be null");
            Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
            Objects.requireNonNull(relevantResources, "relevantResources cannot be null");
            Objects.requireNonNull(relevantPatterns, "relevantPatterns cannot be null");
            Objects.requireNonNull(trimmedPatterns, "trimmedPatterns cannot be null");
            sanitizedRecipes = List.copyOf(sanitizedRecipes);
            relevantStartingResources = relevantStartingResources.copy();
            target = target.copy();
            sanitizedStartingResources = Map.copyOf(new LinkedHashMap<>(sanitizedStartingResources));
            relevantResources = Set.copyOf(relevantResources);
            relevantPatterns = List.copyOf(relevantPatterns);
            trimmedPatterns = List.copyOf(trimmedPatterns);
        }
    }

    public record SolveAndPreviewResult(
        Initialization initialization,
        Optional<DesanitizedRecipeApplicationPath> recipeApplicationPath,
        Preview previewResult
    ) {
        public SolveAndPreviewResult {
            Objects.requireNonNull(initialization, "initialization cannot be null");
            Objects.requireNonNull(recipeApplicationPath, "recipeApplicationPath cannot be null");
            Objects.requireNonNull(previewResult, "previewResult cannot be null");
        }
    }

    public record SolveAndTreePreviewResult(
        Initialization initialization,
        Optional<DesanitizedRecipeApplicationPath> recipeApplicationPath,
        TreePreview previewResult
    ) {
        public SolveAndTreePreviewResult {
            Objects.requireNonNull(initialization, "initialization cannot be null");
            Objects.requireNonNull(recipeApplicationPath, "recipeApplicationPath cannot be null");
            Objects.requireNonNull(previewResult, "previewResult cannot be null");
        }
    }
}

