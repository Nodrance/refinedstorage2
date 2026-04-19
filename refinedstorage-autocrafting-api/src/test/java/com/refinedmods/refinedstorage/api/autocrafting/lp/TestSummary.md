## Not applicable
Tests for things LP does not touch or attempt to replace, such as the PreviewBuilder

- `PreviewBuilderTest.java`
- `TaskPlanTest.java`
- `PreviewTest.java`
- `TreePreviewTest.java`
- `CraftabilityTest.java`
- `PatternTest.java`
- `PatternRepositoryImplTest.java`
- `IngredientTest.java`
- `AmountTest.java`

## No equivalent
Tests for things LP replaces but has no real equivalent for

- `TaskImplTest.java`
- `CraftingSolverCancellationTest.java`

## Todo
Tests which are applicable and have not yet been ported over

- None.

## Changed
Tests that had to have their expectations changed to be compatible with LP, such as which ingredient is considered missing in some Preview tests
Note that all preview-style tests use a dedicated comparison method which doesn't care about the order items appear.

- `LpPreviewTest.java`
- `LpTreePreviewTest.java`
- `LpPreviewTest.shouldExhaustAllPossibleIngredientsWhenRunningOutInSingleRootPatternAndMultipleIngredients`: LP expects `OAK_PLANKS` to be missing instead of `SPRUCE_PLANKS`.
- `LpPreviewTest.shouldNotCalculateForSingleRootPatternSingleChildPatternWSingleIngredientAndAlmostAllResourcesAreAvailable`: LP expects 2 `OAK_LOG` to be available and only 1 `SPRUCE_LOG` to be missing, instead of missing 3 spruce logs.
- `LpPreviewTest.shouldDetectPatternCycles` differs from `PreviewTest.shouldDetectPatternCycles`: LP simply solves the crafting problem instead of throwing an exception.
- `LpTreePreviewTest.shouldExhaustAllPossibleIngredientsWhenRunningOutInSingleRootPatternAndMultipleIngredients`: LP differs from `TreePreviewTest.shouldExhaustAllPossibleIngredientsWhenRunningOutInSingleRootPatternAndMultipleIngredients`.
- `LpTreePreviewTest.shouldNotCalculateForSingleRootPatternSingleChildPatternWSingleIngredientAndAlmostAllResourcesAreAvailable`: LP differs from `TreePreviewTest.shouldNotCalculateForSingleRootPatternSingleChildPatternWSingleIngredientAndAlmostAllResourcesAreAvailable`.
- `LpTreePreviewTest.shouldDetectPatternCycles`: LP differs from `TreePreviewTest.shouldDetectPatternCycles`.

## Unchanged
Tests that are identical to their non-LP counterparts, except for the aforementioned change to the preview assertions.
- `LpStepPlanTest.java`
- `LpCraftabilityTest.java`