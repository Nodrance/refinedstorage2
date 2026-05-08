## CraftingInitializer
### initialize
public static Initialization initialize(
    final RootStorage rootStorage,
    final PatternRepository patternRepository,
    final ResourceKey resource,
    final long amount,
    final CancellationToken cancellationToken
)
RecipeSanitizer.collectRelevantPatterns
RecipeSanitizer.trimFuzzyPatterns
RecipeSanitizer.computeMultiResourceKeyIndex
RecipeSanitizer.toSanitizedRecipes
RecipeAnalyzer.collectRelevantResourceKeys
buildRelevantStartingResources
buildSanitizedStartingResources
validateOverflowInputs

## TaskDispatcher
### addTask
public static Optional<TaskId> addTask(ResourceKey resource, long amount, Actor actor, RecipeApplicationPath path, Collection<Pattern> patterns, boolean notify, SingleStepTaskCreator singleStepTaskCreator, PatternTaskSubmitter patternTaskSubmitter, Predicate<Pattern> hasProvider)
if {
    toSingleStepPlan
    singleStepTaskCreator.create
} else {
    findRootPattern
    if {
        hasProvider.test
    } else {
        DispatcherTask
        patternTaskSubmitter.submit
        dispatcher.getId
    }
}

### findRootPattern
private static Pattern findRootPattern(ResourceKey resource, List<RecipeApplicationStep> steps, Map<UUID, Pattern> patternsById)
loop {
    if {
        pattern.layout().outputs().stream().anyMatch
        return pattern
    }
}
return null

### toSingleStepPlan
private static TaskPlan toSingleStepPlan(ResourceKey requestedResource, long requestedAmount, RecipeApplicationStep step, Map<UUID, Pattern> patternsById, boolean root)
requirePattern
loop {
    // loop over pattern.layout().ingredients()
    // no lp function calls
}
return new TaskPlan

### DispatcherTask.step
public boolean step(RootStorage rootStorage, ExternalPatternSinkProvider sinkProvider, StepBehavior stepBehavior, TaskListener listener)
pruneCompletedSubTasks
if {
    state == TaskState.READY
    state = TaskState.RUNNING
} if {
    cancelled
    cancelActiveSubTasks
    if {
        activeSubTasks.isEmpty
        state = TaskState.COMPLETED
        return true
    }
    state = TaskState.RETURNING_INTERNAL_STORAGE
    return changed
} if {
    state == TaskState.RUNNING
    stepSpecificSubTasks
    pruneCompletedSubTasks
    if {
        strictOrdering
        dispatchStrict
    } else {
        dispatchRelaxed
    }
    stepSpecificSubTasks
    pruneCompletedSubTasks
    if {
        pendingSteps.isEmpty && activeSubTasks.isEmpty
        state = bufferedInternalStorage.isEmpty ? TaskState.COMPLETED : TaskState.RETURNING_INTERNAL_STORAGE
        changed = true
    }
} else if {
    state == TaskState.RETURNING_INTERNAL_STORAGE
    returnBufferedInternalStorage
}
return changed

## RecipeSanitizer
### collectRelevantPatterns
public static List<Pattern> collectRelevantPatterns(List<Pattern> patterns, List<ResourceKey> targetResources)
buildOutputToPatterns
loop {
    if {
        visited.add
        result.add
        loop {
            queue.add
        }
    }
}
return result

### trimFuzzyPatterns
public static List<Pattern> trimFuzzyPatterns(List<Pattern> patterns, Set<ResourceKey> availableResources)
collectCraftableResources
loop {
    trimFuzzyPattern
}
return result

### computeMultiResourceKeyIndex
public static MultiResourceKeyIndex computeMultiResourceKeyIndex(List<Pattern> patterns)
collectOutputResources
loop {
    // resourceParticipation
}
groups.computeIfAbsent
loop {
    result.add
    alreadyGrouped.addAll
}
collectAllResources
loop {
    if {
        alreadyGrouped.add
        result.add
    }
}
buildMemberToMrkMap
return new MultiResourceKeyIndex

### toSanitizedRecipes
public static List<SanitizedRecipe> toSanitizedRecipes(List<Pattern> patterns, List<MultiResourceKey> multiResourceKeys, Map<UUID, Integer> patternPriorities)
buildResourceToKeyMap
return toSanitizedRecipes

## RecipeDesanitizer
### decodeSanitizedResourcesToSanitized
public static ResourcePool decodeSanitizedResourcesToSanitized(ResourcePool sanitizedResources, Map<ResourceKey, Long> remainingSanitizedStorage, CancellationToken cancellationToken)
throwIfCancelled
loop {
    throwIfCancelled
    if {
        amount <= 0L
        continue
    }
    allocateIntoPool
}
return decoded

### decodePlanSteps
public static List<RecipeApplicationStep> decodePlanSteps(List<RecipeApplicationStep> steps, Map<ResourceKey, Long> sanitizedStartingResources, CancellationToken cancellationToken)
throwIfCancelled
loop {
    throwIfCancelled
    decodeSanitizedResourcesToSanitized
    if {
        currentInput != null && currentInput.asMap().equals(sanitizedInput.asMap())
        currentBatchTimesApplied++
        continue
    }
    if {
        currentInput != null
        decodedSteps.add
    }
    currentInput = sanitizedInput
    currentBatchTimesApplied = 1L
}
if {
    currentInput != null
    decodedSteps.add
}
return List.copyOf(decodedSteps)

## RecipeAnalyzer
### collectLeafResources
public static List<MultiResourceKey> collectLeafResources(List<SanitizedRecipe> recipes)
loop {
    produced.addAll
    consumed.addAll
}
loop {
    if {
        !produced.contains(resource)
        leaves.add(resource)
    }
}
return leaves

### detectRecipeCycles
public static CycleDetectionResult detectRecipeCycles(List<SanitizedRecipe> recipes)
loop {
    // build adjacency
}
loop {
    // depthFirstCollectCycles
}
loop {
    inLoopByRecipeId.put
}
loop {
    inLoopByRecipeId.put
    resolvedCycle.add
}
cycles.add
return new CycleDetectionResult

## ResourcePool
### addAll
public void addAll(ResourcePool other)
loop {
    addAmount
}

### subtractAll
public void subtractAll(ResourcePool other)
loop {
    subtractAmount
}

## MultiResourceKey
### contains
public boolean contains(ResourceKey resource)
return members.contains(resource)
