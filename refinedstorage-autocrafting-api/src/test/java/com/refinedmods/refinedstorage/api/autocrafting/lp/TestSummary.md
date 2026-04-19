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
Tests for things LP replaces but has no 1:1 testable equivalent for

- `TaskImplTest.java`

## Changed
Tests that had to have their expectations changed to be compatible with LP, such as which ingredient is considered missing in some Preview tests
Note that all preview-style tests use a dedicated comparison method which doesn't care about the order items appear.

- `PortedPreviewTest.shouldExhaustAllPossibleIngredientsWhenRunningOutInSingleRootPatternAndMultipleIngredients`: LP expects `OAK_PLANKS` to be missing instead of `SPRUCE_PLANKS`.
- `PortedPreviewTest.shouldNotCalculateForSingleRootPatternSingleChildPatternWSingleIngredientAndAlmostAllResourcesAreAvailable`: LP expects 2 `OAK_LOG` to be available and only 1 `SPRUCE_LOG` to be missing, instead of missing 3 spruce logs.
- `PortedPreviewTest.shouldDetectPatternCycles` differs from `PreviewTest.shouldDetectPatternCycles`: LP simply solves the crafting problem instead of throwing an exception.
- `PortedTreePreviewTest.java` differs in the same tests for the same reasons

## Unchanged
Tests that are identical to their non-LP counterparts, except for the aforementioned change to the preview assertions.
- `PortedPreviewTest.java` except where noted above
- `PortedTreePreviewTest.java` except where noted above
- `PortedStepPlanTest.java`
- `PortedCraftabilityTest.java`