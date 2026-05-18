# Test Porting Summary
This file contains a summary of every existing autocrafting test file and how it does or doesn't apply to the LP versions of each class.

## Not applicable
These are things LP does not touch or attempt to replace, such as the PreviewBuilder

- `PreviewBuilderTest.java`
- `PatternTest.java`
- `PatternRepositoryImplTest.java`
- `IngredientTest.java`
- `AmountTest.java`

## Ported
These tests have been changed to use the LP equivalents of the existing implementations, but are otherwise unchanged in setup and assertions, other than noted below.
- `PortedPreviewTest.java`
- `PortedTreePreviewTest.java`
- `PortedTaskPlanTest.java`
- `PortedCraftabilityTest.java`
- `PortedTaskImplTest.java`

## Changes
- All preview tests have been changed to not care about preview ordering. If you can explain to me how the ordering works I'll gladly implement it. Mine follows the rule that every items is guaranteed to be before all its ingredients (not counting loops) and other than that it makes no guarantees.
- `shouldDetectPatternCycles` in both preview tests now simply solves the cycle properly instead of giving up.