package com.refinedmods.refinedstorage.api.autocrafting.lp.sanitization;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.CraftingProblem;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.LinearSolver;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.MultiResourceKey;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.ResourcePool;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.SanitizedRecipe;
import com.refinedmods.refinedstorage.api.core.CoreValidations;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CraftingInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingInitializer.class);

    private CraftingInitializer() {
    }

    public static CraftingProblem initialize(
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
            SanitizedRecipeResourceAnalyzer.collectRelevantResourceKeys(sanitizedRecipes)
        );
        relevantResources.add(targetResource);

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

        LOGGER.debug("[LPT] Validating overflow inputs");
        validateOverflowInputs(rootStorage, allPatterns, amount, targetAmount);

        final CraftingProblem init = new CraftingProblem(
            sanitizedRecipes,
            relevantStartingResources,
            target,
            relevantResources,
            sanitizedStartingResources,
            relevantPatterns,
            trimmedPatterns
        );
        LOGGER.debug(
            "[LP] Initialization complete: {} sanitizedRecipes, {} relevantStartingResources, {} target, "
                + "{} relevantResources, {} relevantPatterns, {} trimmedPatterns",
            init.sanitizedRecipes().size(),
            init.startingResources(),
            init.target(),
            init.relevantResourceKeys().size(),
            init.relevantPatterns().size(),
            init.trimmedPatterns().size()
        );
        return init;
    }

    private static void validateOverflowInputs(
        final RootStorage rootStorage,
        final List<Pattern> allPatterns,
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
        final List<Pattern> patterns,
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

    private static void throwIfCancelled(final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("LP crafting initializer cancelled");
        }
    }

    public static final class LpInputOverflowException extends RuntimeException {
        public LpInputOverflowException(final String message) {
            super(message);
        }

        public LpInputOverflowException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}