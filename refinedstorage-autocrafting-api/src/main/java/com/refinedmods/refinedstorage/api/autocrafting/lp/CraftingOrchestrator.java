package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreviewNode;
import com.refinedmods.refinedstorage.api.core.CoreValidations;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Orchestrates crafting tasks. Contains the main entry points for everything.
public final class CraftingOrchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingOrchestrator.class);

    private CraftingOrchestrator() {
    }

    public static Initialization initialize(
        final RootStorage rootStorage,
        final PatternRepository patternRepository,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        LOGGER.debug("[LPT] Entering initialize()");
        Objects.requireNonNull(rootStorage, "rootStorage cannot be null");
        Objects.requireNonNull(patternRepository, "patternRepository cannot be null");
        Objects.requireNonNull(resource, "resource cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        CoreValidations.validateLargerThanZero(amount, "Requested amount must be greater than 0");
        throwIfCancelled(cancellationToken);

        LOGGER.debug("[LPT] Copying Patterns");
        final List<Pattern> allPatterns = List.copyOf(patternRepository.getAll());
        LOGGER.debug("[LPT] Filtering Patterns");
        final List<Pattern> relevantPatterns = RecipeSanitizer.collectRelevantPatterns(allPatterns, List.of(resource));
        if (relevantPatterns.isEmpty()) {
            throw new IllegalStateException("No pattern found for " + resource);
        }

        LOGGER.debug("[LPT] Building resources");
        final Set<ResourceKey> availableResources = new LinkedHashSet<>();
        for (final ResourceAmount resourceAmount : rootStorage.getAll()) {
            availableResources.add(resourceAmount.resource());
        }
        LOGGER.debug("[LPT] Trimming Patterns");
        final List<Pattern> trimmedPatterns = RecipeSanitizer.trimFuzzyPatterns(
            relevantPatterns,
            availableResources
        );

        LOGGER.debug("[LPT] Computing MRKs");
        final RecipeSanitizer.MultiResourceKeyIndex multiResourceKeyIndex =
            RecipeSanitizer.computeMultiResourceKeyIndex(trimmedPatterns);
        
        LOGGER.debug("[LPT] Getting Pattern Priorities");
        final Map<UUID, Integer> patternPriorities = new LinkedHashMap<>();
        for (final Pattern pattern : relevantPatterns) {
            patternPriorities.put(pattern.id(), patternRepository.getPriority(pattern));
        }

        
        LOGGER.debug("[LPT] Sanitizing Recipes");
        final List<SanitizedRecipe> sanitizedRecipes = RecipeSanitizer.toSanitizedRecipes(
            trimmedPatterns,
            multiResourceKeyIndex,
            patternPriorities
        );


        LOGGER.debug("[LPT] Collecting Relevant Resource Keys");
        final MultiResourceKey targetResource = new MultiResourceKey(List.of(resource));
        final Set<MultiResourceKey> relevantResources = new LinkedHashSet<>(
            SanitizedRecipeAnalyzer.collectRelevantResourceKeys(sanitizedRecipes)
        );
        // relevantResources.add(targetResource);

        LOGGER.debug("[LPT] Building relevant starting resources");
        final ResourcePool relevantStartingResources = RecipeSanitizer.buildRelevantStartingResources(
            rootStorage,
            relevantResources
        );
        LOGGER.debug("[LPT] Building sanitized starting resources");
        final Map<ResourceKey, Long> sanitizedStartingResources = RecipeSanitizer.buildSanitizedStartingResources(
            rootStorage,
            relevantResources
        );


        final ResourcePool target = ResourcePool.empty();
        final long targetAmount = relevantStartingResources.getAmount(targetResource) + amount;
        target.setAmount(targetResource, targetAmount);

        // COMPATABILITY
		// Comment this line to speed things up a bit at the cost of it just normally failing to solve 
		// instead of failing to solve with a fancy "overflow error" screen

        LOGGER.debug("[LPT] Validating overflow inputs");
        validateOverflowInputs(rootStorage, allPatterns, amount, targetAmount);

        final Initialization init = new Initialization(
            sanitizedRecipes,
            relevantStartingResources,
            target,
            sanitizedStartingResources,
            relevantResources,
            relevantPatterns,
            trimmedPatterns
        );
        LOGGER.debug(
            "[LP] Initialization complete: {} sanitizedRecipes, {} relevantStartingResources, {} target, "
                + "{} relevantResources, {} relevantPatterns, {} trimmedPatterns",
            init.sanitizedRecipes().size(), 
            init.relevantStartingResources(), 
            init.target(), 
            init.relevantResources().size(), 
            init.relevantPatterns().size(), 
            init.trimmedPatterns().size()
        );
        return init;
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
            final Initialization initialization = initialize(
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
        } catch (final LpInputOverflowException e) {
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
            final Initialization initialization = initialize(
                rootStorage,
                patternRepository,
                resource,
                amount,
                cancellationToken
            );
            return solve(initialization, cancellationToken);
        } catch (final LpInputOverflowException e) {
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
            maxCalculationInitialization = initialize(
                rootStorage,
                patternRepository,
                resource,
                1L,
                cancellationToken
            );
        } catch (final LpInputOverflowException e) {
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
            final Initialization initialization = initialize(
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
        } catch (final LpInputOverflowException e) {
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
            final Initialization initialization = initialize(
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
            final TreePreview previewResult = calculateTreePreview(
                resource,
                amount,
                rootStorage,
                initialization,
                recipeApplicationPath
            );
            // LOGGER.debug("[LP] Tree preview result: {}", previewResult);
            return new SolveAndTreePreviewResult(initialization, recipeApplicationPath, previewResult);
        } catch (final LpInputOverflowException e) {
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
        LOGGER.debug("[LPT] Entering calculateTreePreview()");
        Objects.requireNonNull(resource, "resource cannot be null");
        Objects.requireNonNull(rootStorage, "rootStorage cannot be null");
        Objects.requireNonNull(initialization, "initialization cannot be null");
        Objects.requireNonNull(recipeApplicationPath, "recipeApplicationPath cannot be null");

        if (recipeApplicationPath.isPresent()) {
            return buildTreePreviewFromPath(
                resource,
                amount,
                rootStorage,
                recipeApplicationPath.get(),
                initialization.relevantPatterns()
            );
        }

        final Preview preview = new Preview(
            PreviewType.NOT_AVAILABLE,
            Collections.emptyList(),
            Collections.emptyList()
        );
        return buildFallbackTreePreview(resource, amount, preview, initialization.relevantPatterns());
    }

    private static TreePreview buildTreePreviewFromPath(
        final ResourceKey resource,
        final long amount,
        final RootStorage rootStorage,
        final DesanitizedRecipeApplicationPath path,
        final Collection<Pattern> relevantPatterns
    ) {
        LOGGER.debug("[LPT] Entering buildTreePreviewFromSteps()");
        final NodeBuilder root = new NodeBuilder(resource, amount);
        final ArrayDeque<PendingNode> frontier = new ArrayDeque<>();
        frontier.add(new PendingNode(root, amount));

        final List<DesanitizedRecipeApplicationStep> reversedSteps = new ArrayList<>(path.steps());
        Collections.reverse(reversedSteps);

        for (final DesanitizedRecipeApplicationStep step : reversedSteps) {
            if (frontier.isEmpty()) {
                break;
            }

            final List<PendingNode> matchedNodes = new ArrayList<>();
            for (final PendingNode pendingNode : frontier) {
                if (getDesanitizedOutputAmount(step.recipe(), pendingNode.node.resource) > 0L) {
                    matchedNodes.add(pendingNode);
                }
            }
            if (matchedNodes.isEmpty()) {
                continue;
            }

            long remainingIterations = step.timesApplied();
            final List<PendingNode> nextNodes = new ArrayList<>();
            for (final PendingNode pendingNode : matchedNodes) {
                frontier.remove(pendingNode);
                if (remainingIterations <= 0) {
                    nextNodes.add(pendingNode);
                    continue;
                }

                final NodeBuilder node = pendingNode.node;
                final long requiredAmount = pendingNode.requiredAmount;
                final long outputPerIteration = getDesanitizedOutputAmount(step.recipe(), node.resource);
                if (outputPerIteration <= 0) {
                    nextNodes.add(pendingNode);
                    continue;
                }

                final long requiredIterations = ceilDiv(requiredAmount, outputPerIteration);
                final long usedIterations = Math.min(remainingIterations, requiredIterations);
                if (usedIterations <= 0) {
                    nextNodes.add(pendingNode);
                    continue;
                }
                remainingIterations -= usedIterations;

                final long craftedAmount = outputPerIteration * usedIterations;
                node.toCraft += craftedAmount;

                for (final Ingredient ingredient : step.recipe().layout().ingredients()) {
                    if (ingredient.inputs().isEmpty()) {
                        continue;
                    }
                    final ResourceKey inputResource = ingredient.inputs().getFirst();
                    final long childAmount = ingredient.amount() * usedIterations;
                    if (childAmount <= 0) {
                        continue;
                    }

                    final NodeBuilder child = node.addChild(inputResource, childAmount);
                    nextNodes.add(new PendingNode(child, childAmount));
                }

                final long unresolvedAmount = requiredAmount - craftedAmount;
                if (unresolvedAmount > 0) {
                    nextNodes.add(new PendingNode(node, unresolvedAmount));
                }
            }
            frontier.addAll(nextNodes);
        }

        final Map<ResourceKey, Long> remainingStorage = new LinkedHashMap<>();
        for (final ResourceAmount resourceAmount : rootStorage.getAll()) {
            remainingStorage.put(resourceAmount.resource(), resourceAmount.amount());
        }

        final Set<ResourceKey> producibleResources = new HashSet<>();
        for (final DesanitizedRecipeApplicationStep step : path.steps()) {
            for (final ResourceAmount output : step.recipe().layout().outputs()) {
                producibleResources.add(output.resource());
            }
        }

        for (final PendingNode pendingNode : frontier) {
            final NodeBuilder node = pendingNode.node;
            final long requiredAmount = pendingNode.requiredAmount;
            final long available = Math.min(requiredAmount, remainingStorage.getOrDefault(node.resource, 0L));
            if (available > 0) {
                node.available += available;
                remainingStorage.put(node.resource, remainingStorage.getOrDefault(node.resource, 0L) - available);
            }
            if (!producibleResources.contains(node.resource)) {
                final long missing = requiredAmount - available;
                if (missing > 0) {
                    node.missing += missing;
                }
            }
        }

        root.amount = Math.max(amount, root.toCraft);

        final PreviewType type = hasMissing(root) ? PreviewType.MISSING_RESOURCES : PreviewType.SUCCESS;
        return new TreePreview(type, root.build(), Collections.emptyList());
    }

    private static TreePreview buildFallbackTreePreview(
        final ResourceKey requestedResource,
        final long requestedAmount,
        final Preview preview,
        final Collection<Pattern> relevantPatterns
    ) {
        LOGGER.debug("[LPT] Entering buildFallbackTreePreview()");
        if (preview.type() == PreviewType.CANCELLED || preview.type() == PreviewType.NOT_AVAILABLE) {
            return new TreePreview(preview.type(), null, preview.outputsOfPatternWithCycle());
        }

        final Map<ResourceKey, PreviewItem> previewItemsByResource = new LinkedHashMap<>();
        for (final PreviewItem item : preview.items()) {
            previewItemsByResource.put(item.resource(), item);
        }

        final PreviewItem rootItem = previewItemsByResource.get(requestedResource);
        final long rootAmount = rootItem == null
            ? requestedAmount
            : rootItem.available() + rootItem.missing() + rootItem.toCraft();
        final NodeBuilder root = new NodeBuilder(requestedResource, rootAmount);
        applyPreviewItem(root, rootItem);

        final Set<ResourceKey> assignedResources = new HashSet<>();
        assignedResources.add(requestedResource);
        attachPatternChildren(root, previewItemsByResource, relevantPatterns, assignedResources, new HashSet<>());

        for (final PreviewItem item : preview.items()) {
            if (assignedResources.contains(item.resource())) {
                continue;
            }
            final long amount = item.available() + item.missing() + item.toCraft();
            final NodeBuilder child = root.addChild(item.resource(), amount);
            applyPreviewItem(child, item);
            assignedResources.add(item.resource());
        }

        return new TreePreview(preview.type(), root.build(), preview.outputsOfPatternWithCycle());
    }

    private static void attachPatternChildren(
        final NodeBuilder node,
        final Map<ResourceKey, PreviewItem> previewItemsByResource,
        final Collection<Pattern> relevantPatterns,
        final Set<ResourceKey> assignedResources,
        final Set<ResourceKey> path
    ) {
        LOGGER.debug("[LPT] Entering attachPatternChildren()");
        if (!path.add(node.resource)) {
            return;
        }

        final Pattern pattern = findPatternProducing(node.resource, relevantPatterns);
        if (pattern == null) {
            path.remove(node.resource);
            return;
        }

        for (final Ingredient ingredient : pattern.layout().ingredients()) {
            final ResourceKey selectedInput = pickInputResourceForIngredient(ingredient, previewItemsByResource);
            if (selectedInput == null || !assignedResources.add(selectedInput)) {
                continue;
            }

            final PreviewItem childItem = previewItemsByResource.get(selectedInput);
            final long childAmount = childItem == null
                ? ingredient.amount()
                : childItem.available() + childItem.missing() + childItem.toCraft();
            final NodeBuilder child = node.addChild(selectedInput, childAmount);
            applyPreviewItem(child, childItem);
            attachPatternChildren(child, previewItemsByResource, relevantPatterns, assignedResources, path);
        }

        path.remove(node.resource);
    }

    private static Pattern findPatternProducing(
        final ResourceKey resource,
        final Collection<Pattern> patterns
    ) {
        LOGGER.debug("[LPT] Entering findPatternProducing()");
        for (final Pattern pattern : patterns) {
            for (final ResourceAmount output : pattern.layout().outputs()) {
                if (output.resource().equals(resource)) {
                    return pattern;
                }
            }
        }
        return null;
    }

    private static ResourceKey pickInputResourceForIngredient(
        final Ingredient ingredient,
        final Map<ResourceKey, PreviewItem> previewItemsByResource
    ) {
        LOGGER.debug("[LPT] Entering pickInputResourceForIngredient()");
        for (final ResourceKey input : ingredient.inputs()) {
            if (previewItemsByResource.containsKey(input)) {
                return input;
            }
        }
        return ingredient.inputs().isEmpty() ? null : ingredient.inputs().getFirst();
    }

    private static void applyPreviewItem(
        final NodeBuilder node,
        final PreviewItem item
    ) {
        LOGGER.debug("[LPT] Entering applyPreviewItem()");
        if (item == null) {
            return;
        }
        node.available += item.available();
        node.missing += item.missing();
        node.toCraft += item.toCraft();
    }

    private static boolean hasMissing(final NodeBuilder node) {
        LOGGER.debug("[LPT] Entering hasMissing()");
        if (node.missing > 0) {
            return true;
        }
        for (final NodeBuilder child : node.children.values()) {
            if (hasMissing(child)) {
                return true;
            }
        }
        return false;
    }

    private static long ceilDiv(final long numerator, final long denominator) {
        LOGGER.debug("[LPT] Entering ceilDiv()");
        return ((numerator - 1) / denominator) + 1;
    }

    private static void validateOverflowInputs(
        final RootStorage rootStorage,
        final Collection<Pattern> allPatterns,
        final long requestedAmount,
        final long targetAmount
    ) {
        final int recipeUpperBound = LinearSolver.Options.defaults().recipeUpperBound();

        validateAmountWithinRecipeUpperBound(requestedAmount, recipeUpperBound, "requested amount");
        validateAmountWithinRecipeUpperBound(targetAmount, recipeUpperBound, "target amount");
        for (final ResourceAmount resourceAmount : rootStorage.getAll()) {
            validateAmountWithinRecipeUpperBound(
                resourceAmount.amount(),
                recipeUpperBound,
                "starting amount for " + resourceAmount.resource()
            );
        }
        validatePatternAmountsWithinRecipeUpperBound(allPatterns, recipeUpperBound);
    }

    private static void validatePatternAmountsWithinRecipeUpperBound(
        final Collection<Pattern> patterns,
        final int recipeUpperBound
    ) {
        for (final Pattern pattern : patterns) {
            for (final Ingredient ingredient : pattern.layout().ingredients()) {
                validateAmountWithinRecipeUpperBound(
                    ingredient.amount(),
                    recipeUpperBound,
                    "ingredient amount in pattern " + pattern.id()
                );
            }
            for (final ResourceAmount output : pattern.layout().outputs()) {
                validateAmountWithinRecipeUpperBound(
                    output.amount(),
                    recipeUpperBound,
                    "output amount in pattern " + pattern.id()
                );
            }
        }
    }

    private static void validateAmountWithinRecipeUpperBound(
        final long amount,
        final int recipeUpperBound,
        final String context
    ) {
        if (amount > recipeUpperBound) {
            throw new LpInputOverflowException(
                context + " exceeds LP recipe upper bound " + recipeUpperBound + ": " + amount
            );
        }
    }

    private static int clampToInt(final long value) {
        LOGGER.debug("[LPT] Entering clampToInt()");
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    private static long getDesanitizedOutputAmount(final DesanitizedRecipe recipe, final ResourceKey resource) {
        long total = 0L;
        for (final ResourceAmount output : recipe.layout().outputs()) {
            if (output.resource().equals(resource)) {
                total += output.amount();
            }
        }
        return total;
    }

    private static void throwIfCancelled(final CancellationToken cancellationToken) {
        LOGGER.debug("[LPT] Entering throwIfCancelled()");
        if (cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("LP crafting initializer cancelled");
        }
    }

    private static final class LpInputOverflowException extends RuntimeException {
        private LpInputOverflowException(final String message) {
            super(message);
        }

        private LpInputOverflowException(final String message, final Throwable cause) {
            super(message, cause);
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

    private record PendingNode(NodeBuilder node, long requiredAmount) {
    }

    private static class NodeBuilder {
        private final ResourceKey resource;
        private final Map<ResourceKey, NodeBuilder> children = new LinkedHashMap<>();
        private long amount;
        private long toCraft;
        private long available;
        private long missing;

        private NodeBuilder(final ResourceKey resource, final long amount) {
            this.resource = resource;
            this.amount = amount;
        }

        private NodeBuilder addChild(final ResourceKey childResource, final long childAmount) {
            final NodeBuilder existing = children.get(childResource);
            if (existing != null) {
                existing.amount += childAmount;
                return existing;
            }
            final NodeBuilder child = new NodeBuilder(childResource, childAmount);
            children.put(childResource, child);
            return child;
        }

        private TreePreviewNode build() {
            return new TreePreviewNode(
                resource,
                amount,
                toCraft,
                available,
                missing,
                children.values().stream().map(NodeBuilder::build).toList()
            );
        }
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

