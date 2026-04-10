A plain-english description of all the LP stuff, both for me to aorganize my thoughts and for anyone coming after me to get a handle on it

# Public records
LpResourceSet: map from resource to amount. Supports negatives.
LpResourceSubset: A list of resources that, for the purposes of LP, are interchangeable. Can be treated as a single item during solving, then expanded later
LpPatternRecipe: A representation of a recipe as a list of input items and a list of output items, with a priority
LpCraftingSolution: set of "use this recipe N times in total" as well as the final inventory after doing that and a list of resources considered relevant
LpExecutionPlanStep: "use this recipe N times"
LpStepPlan: A list of LpExecutionPlanSteps, and a bool for whether there's loops. Describes a full crafting sequence

# Public classes
## LpCraftingSolver
Flow search model: Private subclass: Sets up and solves linear programming problems
### Records
RecipeApplicationPlanResult: a crafting solution, an execution step plan, and a set of items needed to be added, if any are needed
CycleEliminationResult: A recipe application plan result, as well as the recipes that needed to be disabled to make it happen
PlanningOutcome: recipe application plan result, max craftable amount, required base items if relevant, and list of disabled recipes
### Functions
solve: Given sanitized filtered recipes, sanitized filtered starting resources, and a target, solves the LP system and returns a PlanningOutcome
computeRequiredBaseItems: Given those same inputs, returns a resource set you'd need to add to make it solvable
findRecipeApplicationPlanViaCycleElimination: Given the same inputs, starts deleting cycles until the execution planner says there's a result
## LpDispatcherHelper
### Functions
toTaskPlan: Converts a list of LpExecutionPlanSteps into a TaskPlan, which is a format that the rest of RS can use
toSingleStepPlan: Converts an LpExecutionPlanStep into a TaskPlan with one step, the largest step it can currently take. Used to properly dispatch tasks
createDispatcherPlan: Basically just creates a taskplan from passed in values
findRootPattern: Finds the final pattern in a plan
## LpExecutionPlanner
### Functions
buildExecutablePlanFromRecipeUsage: Given a list of recipes that need to be used and an inventory, produces a list of LpExecutionPlanSteps such that the inventory will never go below 0 if they are followed, or returns None if this is impossible
buildRecipeApplicationPlanFromRecipeUsage: Either returns an executable plan, or just adds each recipe application one by one in order if it's impossible to make one
## LpFuzzyExpander
### Records
FuzzyExpansionResult: A list of LpPatternRecipes, A map from LpPatternRecipe to its source pattern, and a list of the item subsets it used
### Functions
expandFuzzyPatterns: Takes in a list of patterns, a list of resources in storage, and a list of resources that can be crafted, and creates disjoint subsets of items that can be represented by a single value in the LP solve. For example, why worry about whether you're using oak or birch planks when every recipe you're using doesn't care about the difference, just store them as "planks".
Once it has those disjoint subsets, it uses them to create psuedo-patterns that each have a concrete resource and aren't fuzzy
collectCraftableResources: Gets the list of items that can be crafted from any of the patterns given
augmentStartingResources: Takes in a set of resources, and adds in the combined resources. For example, adds "5 planks" to the list "2 birch planks and 3 oak planks"
decodeFuzzyResources: Turns resource groups back into concrete resources
decodePlanSteps: Turns a list of LpExecutionPlanSteps with LpResourceSubsets into one with only ResourceKeys
## LpPlanningHelper
### Functions
collectRelevantPatternsForLp: Collects patterns that are part of the target item's crafting tree
## LpPreviewCalculator
### Functions
calculatePreview: The big outer function, given an item and quantity and all patterns and entire storage, returns a calculated preview to display. 
## LpRecipeAnalysis
### Records 
CycleDetectionResult: A map from recipe IDs to whether that recipe is in a loop, and a list of said loops
### Functions
collectRelevantResourceKeys: Given a list of LpPatternRecipes, determine all the resource keys in any input or output
prioritizeAndPruneRelevantRecipes: Given a list of recipes, prunes ones not in the recipe tree and give the rest a priority
collectNonProducibleResources: Given a set of relevant resources, finds the ones that cannot be produced by any of the given recipes
selectTopPriorityRecipesPerOutputResource: Returns a list of recipes that only contains the highest priority ways to make each resource
detectRecipeCycles: Given a list of recipes, produces a CycleDetectionResult
collectLoopClosingRecipeIdsOnTargetBranches: Given a list of recipes, returns the ID of recipes that close loops. If you removed all of these recipes the tree would have no loops.
collectLoopEntryDeficitResourcesOnTargetBranches: Given a list of recipes, returns the items that would be needed to begin each loop
## LpStepPlanCalculator
### Functions
calculateSteps: Another big outer function, given a target item and recipes and storage, returns an LpStepPlan with the steps to craft that item, and whether or not there are loops in the plan.
## LpTaskDispatcher
Just an LP version of TaskImpl that can take in LpStepPlans instead of TaskPlans, and will use the LpDispatcherHelper to convert them into TaskPlans to actually dispatch

Full flow:

1. User clicks the + button, setting the amount in the crafting UI window
2. LinearAutocraftingNetworkComponentImpl calls getPreview 
3. This calls LpPlanningHelper.collectRelevantPatternsForLp and passes the result to LpPreviewCalculator.calculatePreview
4. collectRelevantPatternsForLp collects patterns in the crafting tree
5. calculatePreview sets up the starting resources and expands fuzzy patterns using LpFuzzyExpander
6. It then calls LpCraftingSolver.solve with the expanded patterns, expanded starting resources, and the target
7. LpCraftingSolver.solve sets up and solves the LP problem, returning a PlanningOutcome
8. calculatePreview decodes the fuzzy resources in the PlanningOutcome, and returns a preview to display to the user
9. If there are missing resources, it instead calls buildMissingPreview

1. User then clicks craft, which calls LinearAutocraftingNetworkComponentImpl.startTask
2. This calls collectRelevantPatternsForLp and LpStepPlanCalculator.calculateSteps
3. This then does almost all the same steps except with decodeFuzzyStepsIfNeeded instead of decodeFuzzyResources

1. Lp.solve is called
2. It calls computeMaxCraftableTargetAmount.
3. If 0, calls computeRequiredBaseItemsAndSolution then buildRecipeApplicationPlanResult
4. If > 0, calls findRecipeApplicationPlanViaCycleElimination. If it can find a plan, it returns that with the max craftable amount and no required base items
5. If it cannot, it calls computeRequiredBaseItemsAndSolution and buildRecipeApplicationPlanResult
6. computeRequiredBaseItemsAndSolution narrows down to the best recipe for each resource, then finds loop defecits, then solves with unrestricted required base items.