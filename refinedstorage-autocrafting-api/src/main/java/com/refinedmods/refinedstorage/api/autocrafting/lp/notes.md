A map of this part of code, for me to organize my thoughts and for anyone coming after me

## CraftingOrchestrator
Orchestrates the whole thing. When you request a preview, it sanitizes the inputs with the Sanitizer, then calls the solve function, then the "turn into a preview" function

## RecipeSanitizer
Has a few functions:
- Deletes all irrelevant patterns (patterns that aren't in the crafting tree and so cannot help making the target)
- Removes any irrelevant fuzzies (who cares that twilight forest wood can make sticks when you've never been to the twilight forest and have no twilight forest wood?)
- Splits up the remaining patterns into multi-resource keys, which represent several possible resources. If every relevant recipe doesn't care whether a specific plank is oak or spruce, then the solver doesn't have to either, at least until it comes time to show a preview with concrete resources or dispatch a task with concrete resources. This is the main way we reduce the size of the problem the LP solver has to solve, by combining similar resources together.

## RecipeAnalyzer
Has a few functions as well:
- Find and return recipe cycles
- Find leaf items
- Applies priorities properly to simulate the priority inheritence you get from the traditional system

## CraftingSolver
The big one. This sets up and solves the LP problem by turning every recipe into a variable like "if you use this recipe X times, then subtract 2X planks and add 4X sticks", and adding constraints like "you cannot end with a negative number of sticks" and "you need at least 10 sticks at the end".
It uses LinearSolver.java to actually solve the equations, and ExecutionPlanner.java tells it if the solution it gets can actually be carried out in a world where cause and effect exist. For recipes without cycles this is guaranteed to work first try. For recipes with cycles, it'll "snip" a cycle then try again until one of them works. This can take a long time for large nmbers of cycles, so it has its own seperate timeout token that will return failure if it takes too long, so it can move on to defecit analysis without cancelling the whole preview.
If it can't find a plan even with snipped cycles, or it can't find a solution to the LP problem in the first place, it'll retry the LP solve except it'll let "leaf" items on the crafting tree go negative. This is how it computes required items for recipes you can't make.
If it still can't find a solution, this means there's a loop we can't start (like crafting a netherite template with no starting template). When this happens, we snip all cycles (by removing the recipe that would complete the cycle) which turns it into a tree, and then we can get leaf resources and try again.

## ExecutionPlanner
Basically just turns "apply this recipe X times in total, apply this recipe Y times in total, etc" with no order information into "first apply this recipe A times, then this recipe B times, etc". This filters out things like trying to use an item before making it, because linear programming doesn't understand the flow of time. If it can't find a valid order, then it returns failure, which causes the CraftingSolver to snip a cycle and try again.

## LinearSolver
Actual interface for ojalgo. Sets up and solves the tasks, but knows nothing about the items its working on (that's the job of the higher levels)

## RecipeDesanitizer
Turns the multi-resource keys back into actual resources

