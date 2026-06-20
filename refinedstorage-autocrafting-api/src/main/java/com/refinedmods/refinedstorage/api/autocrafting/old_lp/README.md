# Linear Programming Autocrafting Solver
This is a linear programming based solver for autocrafting in Refined Storage. It is designed to replace the existing backtracking solver.
## Intro: How tf does it work?
Imagine we start with 7 oak planks, and use the sticks recipe X times, and the planks from logs recipe Y times
How many oak planks do we end with? Well, it's the amount we start with, minus the amount we use in the sticks recipe, plus the amount we get from the logs recipe.
This can be written as an equation: `7 - 2X + 4Y`
If we do this for each item, we get a system of equations. Then we add the contraints that you can't end up with negative planks, add the constraint that our target item needs to be the right number (for example if we're crafting 10 of it, we need to end up with at least 10 more than we started with), then we just plug this into an off-the-shelf linear programming solver, and it will give us a solution.
## Further issues to solve
### Too many uses
While this will give us some solution, it won't always be the most efficient. For example, "craft 100 sticks" is technically a solution to "end up with at least 3 more sticks than you started with", but it's not a very good one.
The solution is to add a constraint to each recipe, one by one, minimizing it. We need to do this in priority order, so that when we minimize the lowest priority recipe, it shifts crafting over to higher priority recipes as much as possible. We first find the solution with the least uses of the lowest priority recipe, then try to reduce the second lowest recipe as much as possible without increasing the lowest recipe, then the third lowest, and so on until we reach the best recipe. 
For recipes without cycles, this has been specifically tuned to give the same results as the traditional solver.
### Fuzzy recipes
For fuzzy recipes, we split them into one recipe per possible combination of inputs. For example, a chest with oak and birch would end up as 9 recipes, for each of 0-8 oak and 0-8 birch. This allows the solver to treat them as normal recipes, and it'll find the optimal result.
In order to avoid an explosion of terms, we can limit which items we differentiate. If there are no recipes for birch or oak in our storage, and we have 12 birch planks and 16 oak planks, then we can simply say we have "28 planks" and not worry about the distribution while solving.
The only reason to differentiate two items is if there's a recipe that treats them differently. Either a recipe that takes one and not the other or a recipe that makes one and not the other. For items that are only used in the same way, we can just combine them into a single "fuzzy" item for the purposes of solving. This is done automatically by the solver.
We can then reverse the combination when it comes time to actually preview or execute the calculated plan.
### Borrowing from the future
When cycles are involved, the linear solver can sometimes make solutions that involve borrowing from the future. 
For example, if we have no netherite templates and want 2, it might tell us to use the recipe twice, which will consume 2 patterns, make 4, allow us to give away , and leave us back at 0 where we started. Unfortunately this doesn't work in reality because we'd need to temporarily have a negative number of templates. 
To solve this, we attempt to simulate executing the calculated plan, and make sure we never need to go negative. This also gives us an ordering of steps we can use in execution and tree preview.
If we do need to borrow the future, we start "snipping" cycles by removing the last recipe that closes the loop, until we either get a solution or snip every loop. This is probably not a good way to find solutions for the worst cases, but wikipedia says solving this is equivalent to the petri net reachability problem, which is "EXPSPACE-hard" and has "Ackermann-complete time complexity" so doing it properly is not going to happen.
When there are no cycles we will never need to go negative. If we simply start at the leaves and work our way up, we're always guaranteed to have the items we need. With cycles there are some cases where no leaves exist which is why we need to do the extra work to handle them.
### Finding missing resources
If we can't make the item we want, that means we need to figure out which resources are missing. We do this by taking all the leaf resources and removing the restrictions, letting them go infinitely negative in the solver. Any negative results here corrospond to missing resources, and the more negative they are, the more of that resource we need to add.
With cycles, we do two passes. One where we just do the leaf resources normally (which will catch examples like a netherite template being out of diamonds) and one where we snip all cycles (which will catch examples like a netherite template being out of itself).
## Optimizations
### Irrelevant items/recipes
We don't care about any items or recipes that aren't in the crafting tree of our target, so we simply ignore them
This keeps equation sizes somewhat manageable. We also don't care about fuzzy variants that don't exist in storage and can't be crafted.
### Optimizing missing resources
Once we have a list of missing resources, we run the solver one more time. This time, we tell it to minimize the total number of missing items, and that it can't make any individual missing item worse than it was before. For example, it'll go from 10 oak and 10 birch missing to 9 oak and 10 birch, but not to 0 oak and 11 birch, because that would be worse for birch. This means we'll never tell the user they need more of something than they actually do.
## Futher details
### Task execution
When we execute a plan, we need to make sure we don't waste an item when we're supposed to use in a cycle to make more of it. For example, we don't want to craft netherite helmets before using our templates to make more templates.
To manage this, we keep track of which items are going to be used in a cycle and make sure we only execute patterns that don't use those items.
### Byproducts
The traditional solver only kinda accounts for byproducts, and doesn't show them in the preview correctly. For the LP solver, we simply treat byproducts as products. 
In order to make sure the tests pass, we remove any items that aren't in the direct crafting tree of the final item, as these are byproducts. This doesn't precisely align with the traditional solver, because the traditional solver will lie to your face and tell you you're making less than you are if some of those items are byproducts. Our system always shows accurate counts, but hides final byproducts (this can be disabled by commenting a line)
### Recipe/ingredient prioritization
The traditional solver implicitly prioritizes recipes based on insertion order, and implicitly prioritizes ingredients in reverse order of their position in the fuzzy variant list. 
The LP solver attempts to mimick this by reversing the order of multi resource keys before resolving them back into items, and tracking insertion order of recipes. 
This doesn't perfectly mimick the traditional solver in all cases, but it does in the cases covered by tests. This makes the solver slightly slower and can be disabled by commenting a line if you don't care about perfectly matching the traditional solver's behavior.
### Desanitization
Turning MRKs back into items can be done greedily, by simply taking as much as possible of the first item in the fuzzy variant list from the rootstorage, then the second, and so on. 
If we run out of items, we continue taking the first item of the MRK as this means we're in a deficit. 
Note that due to traditional solver compatibility, we end up using the last item and not the first. 
In my opinion this is worse, because it'll (for example) tell us to gather more cherry planks instead of oak, or more stripped oak logs instead of normal logs, but it does match the old solver's behavior so I kept it.

This could be the basis for a new feature which tells you which combinations of items you're missing, for example telling you that you're missing 10 planks, which could be either oak or birch, instead of telling you that you're missing 10 oak planks and 0 birch planks.

## Actual Implementation Details
Written on 2026-05-19, will probably be out of date by the time you read this, but the general structure is as follows:
- CraftingOrchestrator is the main entry point, which calls the other classes in the right order and manages the overall process. 
- We start by removing irrelevant options in CraftingInitializer
- Then we sanitize the recipes and items to turn them into a more solver-friendly format in RecipeSanitizer. This includes things like splitting fuzzy recipes, and combining items that are used the same way into an MRK.
- Then we run the solver in CraftingSolver, which does logic like finding missing resources, snipping cycles, and optimizing the solution
- This calls LinearSolver which interfaces directly with the LP solver and does things like adding constraints for minimizing recipes and missing resources
- It also calls ExecutionPlanner which simulates executing the plan to make sure we don't borrow from the future, and gives us an ordering of steps for execution and tree preview
- Then, the orchestrator desanitizes the solution back into actual items and recipes in RecipeDesanitizer
- It then sends this to PreviewCalculator or TaskDispatcher depending on whether we're previewing or executing
- Taskdispatcher creates a CyclicTaskImpl if the recipe has cycles or a normal TaskImpl if it doesn't, which manage the actual execution of the plan and keep track of status
- CyclicTaskImpl also uses CycleSafeBudgetPlanner to manage which recipes are safe to execute at any given time without risking breaking a cycle