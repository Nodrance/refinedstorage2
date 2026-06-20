# LP Performance Review

Scope: `refinedstorage-autocrafting-api/.../lp`

This review focuses on runtime and memory bottlenecks in solver, sanitization, preview, and cyclic task scheduling paths.

## Findings (Ordered By Severity)

### 1) Critical: Fuzzy recipe expansion has combinatorial blow-up
- Location:
  - `RecipeSanitizer.expandPattern` (`RecipeSanitizer.java:364`)
  - cross-product block (`RecipeSanitizer.java:394`)
  - recursive allocation generator (`RecipeSanitizer.java:458`, `RecipeSanitizer.java:473`)
- Why it is a bottleneck:
  - The code explicitly builds all stars-and-bars allocations per fuzzy group, then computes a full cross-product across groups.
  - Variant count grows as:
    - For one group: $\binom{n+k-1}{k-1}$ (amount $n$, keys $k$)
    - Across groups: product of those counts
  - This quickly becomes huge and allocates many temporary `Map<MultiResourceKey, Long>` instances (`newVariants`, `combined`, recursive copies).
- Runtime impact scenario:
  - Multiple fuzzy ingredients with moderate amounts can create thousands-to-millions of sanitized variants, causing long pauses and memory pressure before solving even starts.
- Suggested direction:
  - Lazy/generator-style variant iteration, hard caps with fallback heuristics, or LP-level aggregate formulation that avoids explicit expansion.

### 2) High: Cycle detection enumerates all simple cycles
- Location:
  - `SanitizedRecipeCycleAnalyzer.detectRecipeCycles` (`SanitizedRecipeCycleAnalyzer.java:38`)
  - adjacency construction (`SanitizedRecipeCycleAnalyzer.java:47`)
  - DFS cycle enumeration (`SanitizedRecipeCycleAnalyzer.java:189`)
  - cycle canonicalization (`SanitizedRecipeCycleAnalyzer.java:217`)
- Why it is a bottleneck:
  - Builds dense recipe-to-recipe adjacency in $O(R^2)$.
  - Then runs DFS from every start node and attempts to collect/canonicalize every cycle.
  - Number of simple cycles in directed graphs can be exponential.
- Runtime impact scenario:
  - On dense recipe dependency graphs (many interchangeable recipes), cycle detection can dominate planning time and create large intermediate structures (`seenCycles`, `cycleIndices`).
- Suggested direction:
  - Use SCC-based cycle presence/marking for most call-sites; only enumerate specific cycles when strictly required.

### 3) High: Lexicographic optimization repeatedly rebuilds and solves full LP models
- Location:
  - `LinearSolver.lexicographicMinimum` (`LinearSolver.java:62`)
  - per-recipe solve loop (`LinearSolver.java:76`)
  - thread creation per solve (`LinearSolver.java:173`, `LinearSolver.java:208`, `LinearSolver.java:286`)
  - resource constraints build (`LinearSolver.java:437`)
- Why it is a bottleneck:
  - The method does one feasibility solve + one solve per recipe + final solve.
  - Each solve rebuilds objective/constraints over `(resources x recipes)` and constructs a fresh model.
  - Additional per-solve thread startup/teardown adds overhead.
- Runtime impact scenario:
  - If used recipes count is large, end-to-end solve latency scales poorly and can spike CPU usage due to repeated model construction.
- Suggested direction:
  - Reuse a single solver/model where possible, cache coefficient matrices, and avoid per-solve thread creation on fast paths.

### 4) Medium-High: Execution planner backtracking re-sorts/rebuilds candidates each recursion
- Location:
  - recursive entry (`ExecutionPlanner.java:147`)
  - candidate rebuild (`ExecutionPlanner.java:161`, `ExecutionPlanner.java:197`)
  - candidate sort (`ExecutionPlanner.java:212`)
  - batch attempt creation (`ExecutionPlanner.java:240`)
- Why it is a bottleneck:
  - Backtracking is inherently expensive; this implementation recomputes candidate list and sorting at every recursive step.
  - `buildBatchAttempts` allocates/sorts/distincts small lists repeatedly in deep recursion.
- Runtime impact scenario:
  - Near-ambiguous plans with many feasible branches cause substantial overhead from repeated ranking work in addition to recursion itself.
- Suggested direction:
  - Incremental candidate updates, memoization of inventory/remaining states, and branch-and-bound pruning.

### 5) Medium: Tree preview construction does repeated frontier scans and linear removals
- Location:
  - `PreviewCalculator.buildTreePreviewFromSteps` (`PreviewCalculator.java:329`)
  - frontier scan (`PreviewCalculator.java:350`)
  - per-match deque removal (`PreviewCalculator.java:362`)
  - repeated output scan helper (`PreviewCalculator.java:578`)
- Why it is a bottleneck:
  - For each reversed step, it scans the full frontier, then removes matched nodes one-by-one from `ArrayDeque` (linear each removal).
  - `getDesanitizedOutputAmount` linearly scans recipe outputs and is called repeatedly in inner loops.
- Runtime impact scenario:
  - Large preview trees and long step lists can degrade UI responsiveness when building tree previews.
- Suggested direction:
  - Track matched nodes without per-element deque removal, and precompute per-recipe output lookups.

### 6) Medium: Cycle-safe budget recomputation is graph-heavy and can run frequently
- Location:
  - queue recomputation trigger (`CyclicTaskImpl.java:260`, `CyclicTaskImpl.java:431`)
  - budget compute entry (`CycleSafeBudgetPlanner.java:19`)
  - cycle-safety graph build (`CycleSafeBudgetPlanner.java:110`)
  - all-pairs adjacency over active patterns (`CycleSafeBudgetPlanner.java:120`)
  - DFS reachability per pattern (`CycleSafeBudgetPlanner.java:132`, `CycleSafeBudgetPlanner.java:197`)
- Why it is a bottleneck:
  - Rebuilds graph state from scratch with pairwise comparisons and repeated DFS to compute mutual reachability.
  - Complexity trends toward cubic in active pattern count for dense graphs.
- Runtime impact scenario:
  - In cyclic tasks with many active patterns and frequent `queueDirty` transitions, scheduler overhead can become visible.
- Suggested direction:
  - Cache graph artifacts between small changes, or compute SCCs once and update incrementally.

## Notes
- No code changes were made during this review.
- No dedicated performance tests/benchmarks were added in this pass; findings are based on static complexity and allocation-path analysis.
