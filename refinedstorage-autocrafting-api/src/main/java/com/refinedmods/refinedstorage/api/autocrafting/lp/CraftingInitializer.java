package com.refinedmods.refinedstorage.api.autocrafting.lp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bootstraps LP crafting from live network inputs (pattern repository + root storage)
 * by sanitizing patterns and reducing storage/target resources before solving.
 */
public final class CraftingInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(CraftingInitializer.class);
	private CraftingInitializer() {
	}

	public static Initialization initialize(
		final RootStorage rootStorage,
		final PatternRepository patternRepository,
		final ResourceKey resource,
		final long amount,
		final CancellationToken cancellationToken
	) {
		Objects.requireNonNull(rootStorage, "rootStorage cannot be null");
		Objects.requireNonNull(patternRepository, "patternRepository cannot be null");
		Objects.requireNonNull(resource, "resource cannot be null");
		Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
		ResourceAmount.validate(resource, amount);
		throwIfCancelled(cancellationToken);

		// RecipeSanitizer handles conversion from external types to LP types
		final ResourcePool fullStartingResources = RecipeSanitizer.convertToResourcePool(rootStorage.getAll());
		final ResourcePool target = ResourcePool.empty();
		target.setAmount(resource, rootStorage.get(resource) + amount);

		final List<Pattern> allPatterns = patternRepository.getAll().stream()
			.sorted(Comparator.comparing(Pattern::id))
			.toList();

		final List<Pattern> relevantPatterns = RecipeSanitizer.collectRelevantPatterns(allPatterns, target);
		final List<Pattern> sanitizedPatterns = RecipeSanitizer.sanitizeFuzzyPatterns(
			relevantPatterns,
			fullStartingResources
		);
		final List<MultiResourceKey> multiResourceKeys = RecipeSanitizer.computeMultiResourceKeys(sanitizedPatterns);
		final List<ConcreteRecipe> concreteRecipes = RecipeSanitizer.toConcreteRecipes(
			sanitizedPatterns,
			multiResourceKeys,
			fullStartingResources
		);

		final Set<Object> relevantResources = new java.util.LinkedHashSet<>(
			RecipeAnalyzer.collectRelevantResourceKeys(concreteRecipes)
		);
		relevantResources.addAll(target.resourceKeys());

		// RecipeSanitizer also handles building relevant starting resources pool
		final ResourcePool relevantStartingResources = RecipeSanitizer.buildRelevantStartingResources(
			fullStartingResources,
			relevantResources,
			multiResourceKeys
		);

		Initialization init = new Initialization(
			concreteRecipes,
			relevantStartingResources,
			target,
			relevantResources,
			relevantPatterns,
			sanitizedPatterns
		);
		LOGGER.info("[LP] Initialization complete: {} concreteRecipes, {} relevantStartingResources, {} target, {} relevantResources, {} relevantPatterns, {} sanitizedPatterns", 
			init.concreteRecipes().size(), 
			init.relevantStartingResources(), 
			init.target(), 
			init.relevantResources().size(), 
			init.relevantPatterns().size(), 
			init.sanitizedPatterns().size()
		);
		return init;
	}

	public static Optional<RecipeApplicationPath> solve(
		final RootStorage rootStorage,
		final PatternRepository patternRepository,
		final ResourceKey resource,
		final long amount,
		final CancellationToken cancellationToken
	) {
		final Initialization initialization = initialize(
			rootStorage,
			patternRepository,
			resource,
			amount,
			cancellationToken
		);
		LOGGER.info("[LP] Initialization for solve: {} concreteRecipes, {} relevantStartingResources, {} target, {} relevantResources, {} relevantPatterns, {} sanitizedPatterns",
			initialization.concreteRecipes().size(),
			initialization.relevantStartingResources(),
			initialization.target(),
			initialization.relevantResources().size(),
			initialization.relevantPatterns().size(),
			initialization.sanitizedPatterns().size()
		);
		final Optional<RecipeApplicationPath> result = solve(initialization, cancellationToken);
		LOGGER.info("[LP] Solve result: {}", result);
		return result;
	}

	public static Optional<RecipeApplicationPath> solve(
		final Initialization initialization,
		final CancellationToken cancellationToken
	) {
		Objects.requireNonNull(initialization, "initialization cannot be null");
		Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
		throwIfCancelled(cancellationToken);

		return new CraftingSolver(cancellationToken).solve(
			initialization.concreteRecipes(),
			initialization.relevantStartingResources(),
			initialization.target()
		).map(path -> RecipeDesanitizer.decodeRecipeApplicationPath(
			path,
			initialization.relevantStartingResources(),
			cancellationToken
		));
	}

	public static Optional<LpStepPlan> solveToStepPlan(
		final RootStorage rootStorage,
		final PatternRepository patternRepository,
		final ResourceKey resource,
		final long amount,
		final CancellationToken cancellationToken
	) {
		final Initialization initialization = initialize(
			rootStorage,
			patternRepository,
			resource,
			amount,
			cancellationToken
		);
		return solve(initialization, cancellationToken)
			.map(path -> toLpStepPlan(path.steps(), initialization.relevantPatterns()));
	}

	public static long findMaxCraftableAmount(
		final RootStorage rootStorage,
		final PatternRepository patternRepository,
		final ResourceKey resource,
		final long amount,
		final CancellationToken cancellationToken
	) {
		if (cancellationToken.isCancelled()) {
			return 0L;
		}

		long low = 1L;
		long high = 1L;
		while (high < amount && !cancellationToken.isCancelled()) {
			if (solveToStepPlan(rootStorage, patternRepository, resource, high, cancellationToken).isPresent()) {
				low = high;
				high = high > Long.MAX_VALUE / 2 ? amount : Math.min(amount, high * 2);
			} else {
				break;
			}
		}

		long best = 0L;
		while (low <= high && !cancellationToken.isCancelled()) {
			final long middle = low + ((high - low) / 2);
			if (solveToStepPlan(rootStorage, patternRepository, resource, middle, cancellationToken).isPresent()) {
				best = middle;
				low = middle + 1;
			} else {
				high = middle - 1;
			}
		}

		return best;
	}

	public static <T> SolveAndPreviewResult<T> solveAndCalculatePreview(
		final RootStorage rootStorage,
		final PatternRepository patternRepository,
		final ResourceKey resource,
		final long amount,
		final CancellationToken cancellationToken,
		final PreviewCalculator<T> previewCalculator
	) {
		Objects.requireNonNull(previewCalculator, "previewCalculator cannot be null");

		final Initialization initialization = initialize(
			rootStorage,
			patternRepository,
			resource,
			amount,
			cancellationToken
		);
		LOGGER.info("[LP] Initialization for solveAndCalculatePreview: {} concreteRecipes, {} relevantStartingResources, {} target, {} relevantResources, {} relevantPatterns, {} sanitizedPatterns", 
			initialization.concreteRecipes().size(), 
			initialization.relevantStartingResources(), 
			initialization.target(), 
			initialization.relevantResources().size(), 
			initialization.relevantPatterns().size(), 
			initialization.sanitizedPatterns().size()
		);
		final Optional<RecipeApplicationPath> recipeApplicationPath = solve(initialization, cancellationToken);
		LOGGER.info("[LP] Solve result for preview: {}", recipeApplicationPath);
		final T previewResult = previewCalculator.calculate(initialization, recipeApplicationPath);
		LOGGER.info("[LP] Preview result: {}", previewResult);
		return new SolveAndPreviewResult<>(initialization, recipeApplicationPath, previewResult);
	}

	private static LpStepPlan toLpStepPlan(
		final List<RecipeApplicationStep> steps,
		final Collection<Pattern> patterns
	) {
		final Map<UUID, Pattern> patternsById = new LinkedHashMap<>();
		for (final Pattern pattern : patterns) {
			patternsById.put(pattern.id(), pattern);
		}

		final List<LpExecutionPlanStep> lpSteps = new ArrayList<>();
		for (final RecipeApplicationStep step : steps) {
			final ConcreteRecipe recipe = step.recipe();
			final Pattern sourcePattern = patternsById.get(recipe.sourcePatternId());
			if (sourcePattern == null) {
				continue;
			}

			final int priority = clampToInt(recipe.priority());
			final LpPatternRecipe lpRecipe = new LpPatternRecipe(
				sourcePattern,
				toLpResourceSet(recipe.input()),
				toLpResourceSet(recipe.output()),
				priority,
				null
			);
			lpSteps.add(new LpExecutionPlanStep(lpRecipe, step.timesApplied()));
		}

		return new LpStepPlan(lpSteps, false);
	}

	private static int clampToInt(final long value) {
		if (value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (value < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) value;
	}

	private static LpResourceSet toLpResourceSet(final ResourcePool resourcePool) {
		final LpResourceSet result = new LpResourceSet();
		for (final Map.Entry<Object, Long> entry : resourcePool) {
			if (entry.getKey() instanceof ResourceKey resourceKey) {
				result.setAmount(resourceKey, entry.getValue());
			}
		}
		return result;
	}

	private static void throwIfCancelled(final CancellationToken cancellationToken) {
		if (cancellationToken.isCancelled()) {
			throw new java.util.concurrent.CancellationException("LP crafting initializer cancelled");
		}
	}

	@FunctionalInterface
	public interface PreviewCalculator<T> {
		T calculate(Initialization initialization, Optional<RecipeApplicationPath> recipeApplicationPath);
	}

	public record Initialization(
		List<ConcreteRecipe> concreteRecipes,
		ResourcePool relevantStartingResources,
		ResourcePool target,
		Set<Object> relevantResources,
		List<Pattern> relevantPatterns,
		List<Pattern> sanitizedPatterns
	) {
		public Initialization {
			Objects.requireNonNull(concreteRecipes, "concreteRecipes cannot be null");
			Objects.requireNonNull(relevantStartingResources, "relevantStartingResources cannot be null");
			Objects.requireNonNull(target, "target cannot be null");
			Objects.requireNonNull(relevantResources, "relevantResources cannot be null");
			Objects.requireNonNull(relevantPatterns, "relevantPatterns cannot be null");
			Objects.requireNonNull(sanitizedPatterns, "sanitizedPatterns cannot be null");
			concreteRecipes = List.copyOf(concreteRecipes);
			relevantStartingResources = relevantStartingResources.copy();
			target = target.copy();
			relevantResources = Set.copyOf(relevantResources);
			relevantPatterns = List.copyOf(relevantPatterns);
			sanitizedPatterns = List.copyOf(sanitizedPatterns);
		}
	}

	public record SolveAndPreviewResult<T>(
		Initialization initialization,
		Optional<RecipeApplicationPath> recipeApplicationPath,
		T previewResult
	) {
		public SolveAndPreviewResult {
			Objects.requireNonNull(initialization, "initialization cannot be null");
			Objects.requireNonNull(recipeApplicationPath, "recipeApplicationPath cannot be null");
		}
	}
}
