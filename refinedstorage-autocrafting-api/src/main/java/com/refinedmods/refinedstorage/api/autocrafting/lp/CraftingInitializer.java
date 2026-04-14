package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreviewNode;
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
			LOGGER.info("[LPT] Entering initialize()");
		Objects.requireNonNull(rootStorage, "rootStorage cannot be null");
		Objects.requireNonNull(patternRepository, "patternRepository cannot be null");
		Objects.requireNonNull(resource, "resource cannot be null");
		Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
		ResourceAmount.validate(resource, amount);
		throwIfCancelled(cancellationToken);

		final List<Pattern> allPatterns = patternRepository.getAll().stream()
			.sorted(Comparator.comparing(Pattern::id))
			.toList();
		final List<Pattern> relevantPatterns = RecipeSanitizer.collectRelevantPatterns(allPatterns, List.of(resource));

        final Set<ResourceKey> availableResources = new LinkedHashSet<>();
		for (final ResourceAmount resourceAmount : rootStorage.getAll()) {
			availableResources.add(resourceAmount.resource());
		}
		final List<Pattern> sanitizedPatterns = RecipeSanitizer.trimFuzzyPatterns(
			relevantPatterns,
			availableResources
		);

		final RecipeSanitizer.MultiResourceKeyIndex multiResourceKeyIndex = RecipeSanitizer.computeMultiResourceKeyIndex(
			sanitizedPatterns
		);
        final Map<UUID, Integer> patternPriorities = new LinkedHashMap<>();
		for (final Pattern pattern : relevantPatterns) {
			patternPriorities.put(pattern.id(), patternRepository.getPriority(pattern));
		}
        final List<ConcreteRecipe> concreteRecipes = RecipeSanitizer.toConcreteRecipes(
			sanitizedPatterns,
			multiResourceKeyIndex,
			patternPriorities
		);

		final Set<MultiResourceKey> relevantResources = new LinkedHashSet<>(
			RecipeAnalyzer.collectRelevantResourceKeys(concreteRecipes)
		);

		final ResourcePool relevantStartingResources = buildRelevantStartingResources(
			rootStorage,
			relevantResources
		);
		final Map<ResourceKey, Long> concreteStartingResources = buildConcreteStartingResources(
			rootStorage,
			relevantResources
		);

        final MultiResourceKey targetResource = new MultiResourceKey(List.of(resource));
		final ResourcePool target = ResourcePool.empty();
		final long targetAmount = relevantStartingResources.getAmount(targetResource) + amount;
		target.setAmount(targetResource, targetAmount);

		Initialization init = new Initialization(
			concreteRecipes,
			relevantStartingResources,
			target,
			concreteStartingResources,
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
	        LOGGER.info("[LPT] Entering solve(RootStorage, PatternRepository, ResourceKey, long, CancellationToken)");
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
	        LOGGER.info("[LPT] Entering solve(Initialization, CancellationToken)");
		Objects.requireNonNull(initialization, "initialization cannot be null");
		Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
		throwIfCancelled(cancellationToken);

		return new CraftingSolver(cancellationToken).solve(
			initialization.concreteRecipes(),
			initialization.relevantStartingResources(),
			initialization.target()
		).map(path -> desanitizeRecipeApplicationPath(path, initialization.relevantStartingResources(), cancellationToken));
	}

	public static Optional<LpStepPlan> solveToStepPlan(
		final RootStorage rootStorage,
		final PatternRepository patternRepository,
		final ResourceKey resource,
		final long amount,
		final CancellationToken cancellationToken
	) {
	        LOGGER.info("[LPT] Entering solveToStepPlan(RootStorage, PatternRepository, ResourceKey, long, CancellationToken)");
		final Initialization initialization = initialize(
			rootStorage,
			patternRepository,
			resource,
			amount,
			cancellationToken
		);
		return solve(initialization, cancellationToken)
			.map(path -> toLpStepPlan(path.steps(), initialization.relevantPatterns(), initialization.concreteStartingResources(), cancellationToken));
	}

	public static Optional<LpStepPlan> solveToStepPlan(
		final Initialization initialization,
		final CancellationToken cancellationToken
	) {
	        LOGGER.info("[LPT] Entering solveToStepPlan(Initialization, CancellationToken)");
		Objects.requireNonNull(initialization, "initialization cannot be null");
		Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
		throwIfCancelled(cancellationToken);

		return solve(initialization, cancellationToken)
			.map(path -> toLpStepPlan(path.steps(), initialization.relevantPatterns(), initialization.concreteStartingResources(), cancellationToken));
	}

	public static long findMaxCraftableAmount(
		final RootStorage rootStorage,
		final PatternRepository patternRepository,
		final ResourceKey resource,
		final long amount,
		final CancellationToken cancellationToken
	) {
	        LOGGER.info("[LPT] Entering findMaxCraftableAmount()");
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
	        LOGGER.info("[LPT] Entering solveAndCalculatePreview()");
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

	public static TreePreview calculateTreePreview(
		final ResourceKey resource,
		final long amount,
		final RootStorage rootStorage,
		final Initialization initialization,
		final Optional<RecipeApplicationPath> recipeApplicationPath
	) {
	        LOGGER.info("[LPT] Entering calculateTreePreview()");
		Objects.requireNonNull(resource, "resource cannot be null");
		Objects.requireNonNull(rootStorage, "rootStorage cannot be null");
		Objects.requireNonNull(initialization, "initialization cannot be null");
		Objects.requireNonNull(recipeApplicationPath, "recipeApplicationPath cannot be null");

		final Optional<LpStepPlan> stepPlan = recipeApplicationPath.map(path -> toLpStepPlan(
			path.steps(),
			initialization.relevantPatterns(),
			initialization.concreteStartingResources(),
			CancellationToken.NONE
		));
		if (stepPlan.isPresent()) {
			return buildTreePreviewFromSteps(resource, amount, rootStorage, stepPlan.get());
		}

		final Preview preview = new Preview(PreviewType.NOT_AVAILABLE, Collections.emptyList(), Collections.emptyList());
		return buildFallbackTreePreview(resource, amount, preview, initialization.relevantPatterns());
	}

	private static RecipeApplicationPath desanitizeRecipeApplicationPath(
		final RecipeApplicationPath path,
		final ResourcePool availableResources,
		final CancellationToken cancellationToken
	) {
	        LOGGER.info("[LPT] Entering desanitizeRecipeApplicationPath()");
		final List<RecipeApplicationStep> decodedSteps = RecipeDesanitizer.decodePlanSteps(
			path.steps(),
			availableResources,
			cancellationToken
		);

		final RecipeApplicationSet original = path.applicationSet();
		final ResourcePool decodedUsed = RecipeDesanitizer.decodeSanitizedResources(
			original.usedResources(),
			availableResources,
			cancellationToken
		);
		final ResourcePool decodedMissing = RecipeDesanitizer.decodeSanitizedResources(
			original.missingResources(),
			availableResources,
			cancellationToken
		);
		final ResourcePool decodedFinal = RecipeDesanitizer.decodeSanitizedResources(
			original.finalInventoryValues(),
			availableResources,
			cancellationToken
		);

		final RecipeApplicationSet decodedSet = new RecipeApplicationSet(
			original.recipes(),
			original.recipeValues(),
			decodedUsed,
			decodedFinal,
			decodedMissing,
			original.relevantResourceKeys()
		);

		return new RecipeApplicationPath(decodedSet, decodedSteps);
	}

	private static LpStepPlan toLpStepPlan(
		final List<RecipeApplicationStep> steps,
		final Collection<Pattern> patterns,
		final Map<ResourceKey, Long> concreteStartingResources,
		final CancellationToken cancellationToken
	) {
	        LOGGER.info("[LPT] Entering toLpStepPlan()");
		final Map<UUID, Pattern> patternsById = new LinkedHashMap<>();
		for (final Pattern pattern : patterns) {
			patternsById.put(pattern.id(), pattern);
		}
		final Map<ResourceKey, Long> remainingConcreteStorage = RecipeDesanitizer.copyConcreteStorage(concreteStartingResources);

		final List<LpExecutionPlanStep> lpSteps = new ArrayList<>();
		for (final RecipeApplicationStep step : steps) {
			throwIfCancelled(cancellationToken);
			final ConcreteRecipe recipe = step.recipe();
			final Pattern sourcePattern = patternsById.get(recipe.sourcePatternId());
			if (sourcePattern == null) {
				continue;
			}

			final int priority = clampToInt(recipe.priority());
			final LpPatternRecipe lpRecipe = new LpPatternRecipe(
				sourcePattern,
				RecipeDesanitizer.decodeSanitizedResourcesToConcrete(recipe.input(), remainingConcreteStorage, cancellationToken),
				RecipeDesanitizer.convertToConcreteOutputs(recipe.output()),
				priority,
				null
			);
			lpSteps.add(new LpExecutionPlanStep(lpRecipe, step.timesApplied()));
		}

		return new LpStepPlan(lpSteps, false);
	}

	private static TreePreview buildTreePreviewFromSteps(
		final ResourceKey resource,
		final long amount,
		final RootStorage rootStorage,
		final LpStepPlan stepPlan
	) {
	        LOGGER.info("[LPT] Entering buildTreePreviewFromSteps()");
		final NodeBuilder root = new NodeBuilder(resource, amount);
		final ArrayDeque<PendingNode> frontier = new ArrayDeque<>();
		frontier.add(new PendingNode(root, amount));

		final List<LpExecutionPlanStep> reversedSteps = new ArrayList<>(stepPlan.steps());
		Collections.reverse(reversedSteps);

		for (final LpExecutionPlanStep step : reversedSteps) {
			if (frontier.isEmpty()) {
				break;
			}

			final List<PendingNode> matchedNodes = new ArrayList<>();
			for (final PendingNode pendingNode : frontier) {
				if (step.recipe().output().getAmount(pendingNode.node.resource) > 0) {
					matchedNodes.add(pendingNode);
				}
			}
			if (matchedNodes.isEmpty()) {
				continue;
			}

			long remainingIterations = step.iterations();
			final List<PendingNode> nextNodes = new ArrayList<>();
			for (final PendingNode pendingNode : matchedNodes) {
				frontier.remove(pendingNode);
				if (remainingIterations <= 0) {
					nextNodes.add(pendingNode);
					continue;
				}

				final NodeBuilder node = pendingNode.node;
				final long requiredAmount = pendingNode.requiredAmount;
				final long outputPerIteration = step.recipe().output().getAmount(node.resource);
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

				for (final var input : step.recipe().input()) {
					final long childAmount = input.getValue() * usedIterations;
					if (childAmount <= 0) {
						continue;
					}

					final NodeBuilder child = node.addChild(input.getKey(), childAmount);
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
		for (final LpExecutionPlanStep step : stepPlan.steps()) {
			for (final var output : step.recipe().output()) {
				producibleResources.add(output.getKey());
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

		final PreviewType type = hasMissing(root) ? PreviewType.MISSING_RESOURCES : PreviewType.SUCCESS;
		return new TreePreview(type, root.build(), outputsOfPatternWithCycle(stepPlan));
	}

	private static List<ResourceAmount> outputsOfPatternWithCycle(final LpStepPlan stepPlan) {
			LOGGER.info("[LPT] Entering outputsOfPatternWithCycle()");
		if (!stepPlan.hasRecipeCycles()) {
			return Collections.emptyList();
		}

		final Set<ResourceAmount> outputs = new HashSet<>();
		for (final LpExecutionPlanStep step : stepPlan.steps()) {
			outputs.addAll(step.recipe().pattern().layout().outputs());
		}
		return List.copyOf(outputs);
	}

	private static TreePreview buildFallbackTreePreview(
		final ResourceKey requestedResource,
		final long requestedAmount,
		final Preview preview,
		final Collection<Pattern> relevantPatterns
	) {
	        LOGGER.info("[LPT] Entering buildFallbackTreePreview()");
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
	        LOGGER.info("[LPT] Entering attachPatternChildren()");
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
	        LOGGER.info("[LPT] Entering findPatternProducing()");
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
	        LOGGER.info("[LPT] Entering pickInputResourceForIngredient()");
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
	        LOGGER.info("[LPT] Entering applyPreviewItem()");
		if (item == null) {
			return;
		}
		node.available += item.available();
		node.missing += item.missing();
		node.toCraft += item.toCraft();
	}

	private static boolean hasMissing(final NodeBuilder node) {
			LOGGER.info("[LPT] Entering hasMissing()");
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
			LOGGER.info("[LPT] Entering ceilDiv()");
		return ((numerator - 1) / denominator) + 1;
	}

	private static int clampToInt(final long value) {
			LOGGER.info("[LPT] Entering clampToInt()");
		if (value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (value < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) value;
	}

	private static ResourcePool buildRelevantStartingResources(
		final RootStorage rootStorage,
		final Collection<MultiResourceKey> relevantResources
	) {
	        LOGGER.info("[LPT] Entering buildRelevantStartingResources()");
		final ResourcePool result = ResourcePool.empty();
		final Set<MultiResourceKey> processedMultiResourceKeys = new LinkedHashSet<>();

		for (final MultiResourceKey multiResourceKey : relevantResources) {
			if (!processedMultiResourceKeys.add(multiResourceKey)) {
				continue;
			}

			long total = 0L;
			for (final ResourceKey member : multiResourceKey.members()) {
				total += rootStorage.get(member);
			}
			if (total > 0L) {
				result.setAmount(multiResourceKey, total);
			}
		}

		return result;
	}

	private static Map<ResourceKey, Long> buildConcreteStartingResources(
		final RootStorage rootStorage,
		final Collection<MultiResourceKey> relevantResources
	) {
		final Map<ResourceKey, Long> result = new LinkedHashMap<>();
		for (final MultiResourceKey multiResourceKey : relevantResources) {
			for (final ResourceKey member : multiResourceKey.members()) {
				result.putIfAbsent(member, rootStorage.get(member));
			}
		}
		return Map.copyOf(result);
	}

	private static void throwIfCancelled(final CancellationToken cancellationToken) {
			LOGGER.info("[LPT] Entering throwIfCancelled()");
		if (cancellationToken.isCancelled()) {
			throw new java.util.concurrent.CancellationException("LP crafting initializer cancelled");
		}
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

	@FunctionalInterface
	public interface PreviewCalculator<T> {
		T calculate(Initialization initialization, Optional<RecipeApplicationPath> recipeApplicationPath);
	}

	public record Initialization(
		List<ConcreteRecipe> concreteRecipes,
		ResourcePool relevantStartingResources,
		ResourcePool target,
		Map<ResourceKey, Long> concreteStartingResources,
		Set<MultiResourceKey> relevantResources,
		List<Pattern> relevantPatterns,
		List<Pattern> sanitizedPatterns
	) {
		public Initialization {
			Objects.requireNonNull(concreteRecipes, "concreteRecipes cannot be null");
			Objects.requireNonNull(relevantStartingResources, "relevantStartingResources cannot be null");
			Objects.requireNonNull(target, "target cannot be null");
			Objects.requireNonNull(concreteStartingResources, "concreteStartingResources cannot be null");
			Objects.requireNonNull(relevantResources, "relevantResources cannot be null");
			Objects.requireNonNull(relevantPatterns, "relevantPatterns cannot be null");
			Objects.requireNonNull(sanitizedPatterns, "sanitizedPatterns cannot be null");
			concreteRecipes = List.copyOf(concreteRecipes);
			relevantStartingResources = relevantStartingResources.copy();
			target = target.copy();
			concreteStartingResources = Map.copyOf(new LinkedHashMap<>(concreteStartingResources));
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
