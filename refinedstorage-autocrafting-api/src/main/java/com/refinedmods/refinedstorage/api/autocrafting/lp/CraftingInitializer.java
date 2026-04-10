package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bootstraps LP crafting from live network inputs (pattern repository + root storage)
 * by sanitizing patterns and reducing storage/target resources before solving.
 */
public final class CraftingInitializer {
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

		final ResourcePool fullStartingResources = ResourcePool.fromResourceAmounts(rootStorage.getAll());
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
			multiResourceKeys
		);

		final Set<ResourceKey> relevantResources = new LinkedHashSet<>(
			RecipeAnalyzer.collectRelevantResourceKeys(concreteRecipes)
		);
		relevantResources.addAll(target.resourceKeys());

		final ResourcePool relevantStartingResources = filterResourcePool(fullStartingResources, relevantResources);

		return new Initialization(
			concreteRecipes,
			relevantStartingResources,
			target,
			relevantResources,
			relevantPatterns,
			sanitizedPatterns
		);
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
		return new CraftingSolver(cancellationToken).solve(
			initialization.concreteRecipes(),
			initialization.relevantStartingResources(),
			initialization.target()
		);
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
		final Optional<RecipeApplicationPath> recipeApplicationPath = new CraftingSolver(cancellationToken).solve(
			initialization.concreteRecipes(),
			initialization.relevantStartingResources(),
			initialization.target()
		);
		final T previewResult = previewCalculator.calculate(initialization, recipeApplicationPath);
		return new SolveAndPreviewResult<>(initialization, recipeApplicationPath, previewResult);
	}

	private static ResourcePool filterResourcePool(
		final ResourcePool source,
		final Collection<ResourceKey> relevantResources
	) {
		final ResourcePool result = ResourcePool.empty();
		for (final ResourceKey resource : relevantResources) {
			final long amount = source.getAmount(resource);
			if (amount > 0L) {
				result.setAmount(resource, amount);
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
		Set<ResourceKey> relevantResources,
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
