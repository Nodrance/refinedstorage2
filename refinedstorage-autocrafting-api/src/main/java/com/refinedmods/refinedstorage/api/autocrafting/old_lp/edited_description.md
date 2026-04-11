A plain-english description of all the LP stuff, both for me to aorganize my thoughts and for anyone coming after me to get a handle on it

# Public records
# Public classes
## LpCraftingSolver
### Records
### Functions
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
### Functions
decodeFuzzyResources: Turns resource groups back into concrete resources
decodePlanSteps: Turns a list of LpExecutionPlanSteps with LpResourceSubsets into one with only ResourceKeys
## LpPlanningHelper
### Functions
## LpPreviewCalculator
### Functions
calculatePreview: The big outer function, given an item and quantity and all patterns and entire storage, returns a calculated preview to display. 
## LpRecipeAnalysis
### Records 
### Functions
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