# Orchestrator
Thin coordination layer that runs the LP crafting flow in the right order and keeps the solved and unsolved branches separated.

Compared to the old LP pipeline, the main structural change is that the orchestrator should choose between the craftable and uncraftable paths early, instead of carrying one mixed result shape through solving, previewing, desanitizing, and task creation.

# Sanitizer
Main sanitization entrypoint. Turns recipes and items into a more solver-friendly format. This includes splitting fuzzy recipes, combining items that are used the same way into an MRK, and preserving enough structure for later deficit analysis and preview generation.

The old README already covered the broad sanitization ideas, but the rewrite needs to make the output more explicit about cycle information, byproducts, and which distinctions must survive into the unsolved branch.
## RelevancyFilter
Filters out items outside the crafting tree of the target item, and recipes that use them. This keeps the equation sizes manageable.

After this is done, relevantItems should still contain all items in the target crafting tree as well as byproducts produced by relevant recipes, and relevantRecipes should contain all recipes that create those items. Irrelevant fuzzy variants that do not matter for solving can still be dropped here.
## IngredientAnalyzer
Analyzes the ingredients of each recipe, and combines items that are used in the same way into an MRK. For example, if a recipe for sticks can use either oak or birch planks, and no other recipe differentiates them, it will combine those into a single MRK that represents "oak or birch". This reduces the number of variables in the equations, which makes them easier to solve.

This is one of the main places where the rewrite must preserve the old solver's broad behavior without accidentally merging items that later stages still need to distinguish.
## Defuzzer
Splits fuzzy recipes into one recipe per possible combination of inputs. For example, a chest that can be made with oak or birch or any combination would end up as 9 recipes, for each of 0-8 oak and 0-8 birch. This is necessary because the solver needs to know exactly how many of each item is being used in each recipe, and fuzzy recipes do not provide that information.

This still assumes another recipe differentiates oak from birch. If no other recipe differentiates them, the IngredientAnalyzer should already have merged them into an MRK, so the split is unnecessary.
## CycleAnalyzer
Identifies cycles and stores the information for later steps to reference. A cycle is a set of recipes that can produce each other's inputs, such as a tree farm that produces saplings which can be planted to produce more trees.

This information needs to survive sanitization because later stages use it both for safe execution ordering and for peak-resource accounting.
## Prioritizer
Applies lexicographic priority to recipes. This is used to match the old behavior of recipe prioritization as closely as possible.

The old README also called out ingredient order and insertion order effects; those belong here conceptually even if the exact mechanism changes.

# Solver
Goes through the full solution path and produces either a SolvedCraftingProblem or an UnsolvableCraftingProblem. The former contains a full crafting plan that can be executed, while the latter contains information about why the problem is unsolvable and can drive the missing-items preview.

This is the biggest architectural split compared to the old LP solver. The previous system kept solving, verification, deficit reporting, and preview preparation closer together; the rewrite should make craftable and uncraftable outputs first-class separate products.
## PotentialSolvabilityChecker
Checks how many of the target item can be crafted with the given inputs, with borrowing from the future. This is used to quickly rule out impossible situations without having to go through the full solving process, which can be expensive. It does this by attempting to maximize LP production with no restrictions.

This should be the first hard branch point between the craftable and uncraftable flows.
## PotentialSolutionGenerator
Generates PotentialSolutions, which is a list of which recipes to apply and how many times, but no order information. These may involve borrowing from the future.

At this stage the solver still has a mathematical answer, not an executable one.
## LpEngine
Wrapper around ojalgo that solves LP problems. It does this by converting the crafting problem into a set of linear equations, then using ojalgo to solve them. It also owns the solver-specific details such as lexicographic optimization and objective ordering.

The old README described this as the place where recipe minimization and other LP-specific constraints live; that still applies conceptually.
## PotentialSolutionVerifier
Verifies that a PotentialSolution is actually valid and can be executed in a real crafting process. It does this by identifying items that can be used in multiple different chains and ensuring the chosen ordering respects the flow of time.

This section will need to be largely rewritten compared to how it was before, and the output format will be completely different. If verification fails, the failure data should feed the uncraftable branch rather than being folded back into the solved path.
## SolvedCraftingProblem
Contains the data about a crafting plan that can be executed. This includes the set of steps to take and how many times to take each step. For critical items, it includes information about which order the steps using those items must be executed in. Can be used to make a preview or used to actually execute the crafting plan.

This should stay purely on the craftable side of the split.
## MaxUseAnalyzer
Analyzes a solved problem and determines minimum peak resource usage. This is used to tell cyclic task execution what it needs to grab. For example, if the plan involves growing seeds into more seeds, it needs to start with at least one seed, even if the net is +10 seeds it still needs 1 to start with. Compared to a recipe like logs into sticks, where 4 planks are made and consumed, but they can be made before they are consumed, so the peak usage is 0.

This should use the verifier's ordering information to simulate the crafting process and track how many of each item is being used at each step, then take the maximum value for each item across the entire process. It does not need to re-derive the acyclic part of the plan, because the LP result already guarantees that those parts do not borrow from the future.
## DefecitAnalyzer
Analyzes a PotentialSolution that failed verification to determine which items were responsible for the failure. This is used to generate the missing-items preview and to provide information in the UnsolvableCraftingProblem about which items are missing.

Has two passes: The first looks for items outside the loop, meaning leaf nodes in the crafting tree, that are used in a way that cannot be satisfied. For example, not enough water to make seeds. The second pass snips all loops and then does the same thing, which identifies items inside loops that are used in a way that cannot be satisfied. For example, no seeds to grow into more seeds.
## DefecitOptimizer
After at least one deficit scenario has been found, this looks for another scenario that is strictly better. For example, using more efficient but lower priority recipes to produce more of the critical item, which may reduce the amount of the deficit item needed. This guarantees the missing-items preview is as good as possible, which is important for user experience.
## UnsolvableCraftingProblem
Contains the missing-item report for a crafting problem. Can be used to make a preview but cannot be executed.

This split between SolvedCraftingProblem and UnsolvableCraftingProblem is net new, and it should stay explicit because the follow-up processing is different for each branch.
# Desanitizer
Turns sanitized solver output back into real patterns and resources. This involves turning sanitized item keys back into real item keys and desanitizing the recipes.

The old LP code mixed craftable and uncraftable handling here. The rewrite should split this boundary:
- solved path: map sanitized keys back onto real items in storage, reconstruct the executable step plan, and preserve the cycle ordering needed by execution and tree preview;
- unsolved path: map the items that would be used, then map remaining deficits back onto real items and patterns to drive the missing-items preview.

If the solved and unsolved paths share helpers, those helpers should stay low-level and should not accept both problem types as one abstraction.
# Previews and tasks
Preview generation should also split by outcome.

The craftable preview will be generated from the SolvedCraftingProblem and should reuse the execution ordering information. The uncraftable preview will be generated from the UnsolvableCraftingProblem and should optimize the missing-resource display independently.

Task execution stays on the craftable side only. Cyclic tasks still need cycle-safe budgeting so they do not consume resources that are required later in the same loop.

# Change hotspots
This rewrite will need coordinated changes in:
- Orchestrator wiring, because it should branch earlier between solved and unsolved flows.
- Solver outputs, because solved and unsolved results should stop sharing one mixed result shape.
- Desanitization, because it should stop accepting either problem type and become outcome-specific.
- Preview builders, because solved and unsolved previews now take different inputs.
- Task dispatch and cycle budgeting, because only solved plans should reach execution, but they still need the cycle metadata from solving.
- Tests, especially preview, deficit, and cycle-behavior tests, because the output shape and branching assumptions will change.
