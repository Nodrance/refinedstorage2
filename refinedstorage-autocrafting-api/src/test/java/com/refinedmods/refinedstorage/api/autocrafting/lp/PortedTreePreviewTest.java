package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.CancelledCancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.CraftingInitializer;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreviewNode;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.patterns;
import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.storage;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternFixtures.SIGN_PATTERN;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternFixtures.STICKS_PATTERN;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.CRAFTING_TABLE;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_LOG;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_PLANKS;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.SIGN;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.SPRUCE_LOG;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.SPRUCE_PLANKS;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.STICKS;
import static com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreviewBuilder.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortedTreePreviewTest {
        @Test
        void shouldNotCalculateForPatternThatIsNotFound() {
            final RootStorage storage = storage();
            final PatternRepository patterns = patterns();

            final Executable action = () -> calculateTree(storage, patterns, CRAFTING_TABLE, 1, CancellationToken.NONE);

            final IllegalStateException e = assertThrows(IllegalStateException.class, action);
            assertThat(e).hasMessage("No pattern found for " + CRAFTING_TABLE);
        }
    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void shouldNotCalculateWithInvalidRequestedAmount(final long requestedAmount) {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns();

        final Executable action = () -> calculateTree(storage, patterns, CRAFTING_TABLE, requestedAmount,
            CancellationToken.NONE);

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, action);
        assertThat(e).hasMessage("Requested amount must be greater than 0");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2})
    void shouldCalculateForSingleRootPatternSingleIngredientAndAllResourcesAreAvailable(final long requestedAmount) {
        final RootStorage storage = storage(new ResourceAmount(OAK_PLANKS, 8));
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, requestedAmount,
            CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.SUCCESS, CRAFTING_TABLE, requestedAmount)
            .node(OAK_PLANKS, 4 * requestedAmount).available(4 * requestedAmount).end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2})
    void shouldCalculateForSingleRootPatternSingleIngredientSpreadOutOverMultipleIngredientsAndThereAreMissingResources(
        final long requestedAmount
    ) {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 4).build(),
            pattern()
                .ingredient(OAK_PLANKS, 1)
                .ingredient(OAK_PLANKS, 1)
                .ingredient(OAK_PLANKS, 1)
                .ingredient(OAK_PLANKS, 1)
                .output(CRAFTING_TABLE, 1)
                .build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, requestedAmount,
            CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.MISSING_RESOURCES, CRAFTING_TABLE, requestedAmount)
            .node(OAK_PLANKS, 4 * requestedAmount).toCraft(4 * requestedAmount)
            .node(OAK_LOG, requestedAmount).missing(requestedAmount).end()
            .end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldNotCalculateForSingleRootPatternSingleIngredientAndAlmostAllResourcesAreAvailable() {
        final RootStorage storage = storage(new ResourceAmount(OAK_PLANKS, 8));
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 3, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.MISSING_RESOURCES, CRAFTING_TABLE, 3)
            .node(OAK_PLANKS, 12).available(8).missing(4).end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldCalculateWithSingleRootPatternWithMultipleIngredientAndMultipleAreCraftableButOnly1HasEnoughResources() {
        final RootStorage storage = storage(new ResourceAmount(SPRUCE_LOG, 1));
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 4).build(),
            pattern().ingredient(SPRUCE_LOG, 1).output(SPRUCE_PLANKS, 4).build(),
            pattern().ingredient(4).input(OAK_PLANKS).input(SPRUCE_PLANKS).end().output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 1, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.SUCCESS, CRAFTING_TABLE, 1)
            .node(SPRUCE_PLANKS, 4).toCraft(4).node(SPRUCE_LOG, 1).available(1).end().end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldPrioritizeResourcesThatWeHaveMostOfInStorageForSingleRootPatternAndMultipleIngredients() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_PLANKS, 4 * 10),
            new ResourceAmount(SPRUCE_PLANKS, 4 * 5)
        );
        final PatternRepository patterns = patterns(
            pattern().ingredient(4).input(SPRUCE_PLANKS).input(OAK_PLANKS).end().output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 11, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.SUCCESS, CRAFTING_TABLE, 11)
            .node(OAK_PLANKS, 4 * 10).available(4 * 10).end()
            .node(SPRUCE_PLANKS, 4).available(4).end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldExhaustAllPossibleIngredientsWhenRunningOutInSingleRootPatternAndMultipleIngredients() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_PLANKS, 4 * 10),
            new ResourceAmount(SPRUCE_PLANKS, 4 * 5)
        );
        final PatternRepository patterns = patterns(
            pattern().ingredient(4).input(OAK_PLANKS).input(SPRUCE_PLANKS).end().output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 16, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.MISSING_RESOURCES, CRAFTING_TABLE, 16)
            .node(OAK_PLANKS, 44).available(40).missing(4).end()
            .node(SPRUCE_PLANKS, 20).available(20).end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldExhaustAllPossibleIngredientsWhenRunningOutInSingleRootPatternAndMultipleCraftableIngredients() {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 4).build(),
            pattern().ingredient(SPRUCE_LOG, 1).output(SPRUCE_PLANKS, 4).build(),
            pattern().ingredient(4).input(OAK_PLANKS).input(SPRUCE_PLANKS).end().output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 1, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.MISSING_RESOURCES, CRAFTING_TABLE, 1)
            .node(SPRUCE_PLANKS, 4).toCraft(4).node(SPRUCE_LOG, 1).missing(1).end().end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldCalculateForMultipleRootPatternsAndSingleIngredientAndAllResourcesAreAvailable() {
        final RootStorage storage = storage(new ResourceAmount(SPRUCE_PLANKS, 8));
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build(),
            pattern().ingredient(SPRUCE_PLANKS, 4).output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 2, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.SUCCESS, CRAFTING_TABLE, 2)
            .node(SPRUCE_PLANKS, 8).available(8).end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldNotCalculateForMultipleRootPatternsAndSingleIngredientAndAlmostAllResourcesAreAvailable() {
        final RootStorage storage = storage(new ResourceAmount(SPRUCE_PLANKS, 8));
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build(),
            pattern().ingredient(SPRUCE_PLANKS, 8).output(CRAFTING_TABLE, 2).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 3, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.MISSING_RESOURCES, CRAFTING_TABLE, 4)
            .node(SPRUCE_PLANKS, 16).available(8).missing(8).end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldCalculateForSingleRootPatternAndSingleChildPatternWithSingleIngredientAndAllResourcesAreAvailable() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_PLANKS, 3),
            new ResourceAmount(OAK_LOG, 3)
        );
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 4).build(),
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 3, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.SUCCESS, CRAFTING_TABLE, 3)
            .node(OAK_PLANKS, 12).toCraft(12)
            .node(OAK_LOG, 3).available(3).end()
            .end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldNotCalculateForSingleRootPatternSingleChildPatternWSingleIngredientAndAlmostAllResourcesAreAvailable() {
        final RootStorage storage = storage(new ResourceAmount(OAK_LOG, 2));
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 4).build(),
            pattern().ingredient(SPRUCE_LOG, 1).output(OAK_PLANKS, 4).build(),
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 3, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.MISSING_RESOURCES, CRAFTING_TABLE, 3)
            .node(OAK_PLANKS, 12).toCraft(12)
            .node(OAK_LOG, 2).available(2).end()
            .node(SPRUCE_LOG, 1).missing(1).end()
            .end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldCraftMoreIfNecessaryIfResourcesFromInternalStorageAreUsedUp() {
        final RootStorage storage = storage(new ResourceAmount(OAK_LOG, 4));
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 2).build(),
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build(),
            STICKS_PATTERN,
            SIGN_PATTERN
        );

        final TreePreview actual = calculateTree(storage, patterns, SIGN, 1, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.SUCCESS, SIGN, 3)
            .node(OAK_PLANKS, 6).toCraft(6).node(OAK_LOG, 3).available(3).end().end()
            .node(STICKS, 1).toCraft(4).node(OAK_PLANKS, 2).toCraft(2).node(OAK_LOG, 1).available(1).end().end()
            .end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldCraftMoreIfNecessaryIfResourcesFromStorageAreUsedUp() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_PLANKS, 6),
            new ResourceAmount(OAK_LOG, 1)
        );
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 2).build(),
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build(),
            STICKS_PATTERN,
            SIGN_PATTERN
        );

        final TreePreview actual = calculateTree(storage, patterns, SIGN, 1, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.SUCCESS, SIGN, 3)
            .node(OAK_PLANKS, 6).available(4).toCraft(2).node(OAK_LOG, 1).available(1).end().end()
            .node(STICKS, 1).toCraft(4).node(OAK_PLANKS, 2).available(2).end().end()
            .build();

        assertTreeEquals(actual, expected);
    }

    private static Stream<Arguments> provideMissingResourcesPreview() {
        return Stream.of(
            Arguments.of(1, tree(PreviewType.MISSING_RESOURCES, SIGN, 3)
                .node(OAK_PLANKS, 6).toCraft(6).node(OAK_LOG, 3).available(3).end().end()
                .node(STICKS, 1).toCraft(4).node(OAK_PLANKS, 2).toCraft(2).node(OAK_LOG, 1).missing(1).end().end()
                .end().build()),
            Arguments.of(4, tree(PreviewType.MISSING_RESOURCES, SIGN, 6)
                .node(OAK_PLANKS, 12).toCraft(12).node(OAK_LOG, 6).available(3).missing(3).end().end()
                .node(STICKS, 2).toCraft(4).node(OAK_PLANKS, 2).toCraft(2).node(OAK_LOG, 1).missing(1).end().end()
                .end().build()),
            Arguments.of(20, tree(PreviewType.MISSING_RESOURCES, SIGN, 21)
                .node(OAK_PLANKS, 42).toCraft(42).node(OAK_LOG, 21).available(3).missing(18).end().end()
                .node(STICKS, 7).toCraft(8).node(OAK_PLANKS, 4).toCraft(4).node(OAK_LOG, 2).missing(2).end().end()
                .end().build())
        );
    }

    @ParameterizedTest
    @MethodSource("provideMissingResourcesPreview")
    void shouldKeepCalculatingEvenIfResourcesAreMissing(final long requestedAmount, final TreePreview expectedTree) {
        final RootStorage storage = storage(new ResourceAmount(OAK_LOG, 3));
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 2).build(),
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build(),
            STICKS_PATTERN,
            SIGN_PATTERN
        );

        final TreePreview actual = calculateTree(storage, patterns, SIGN, requestedAmount, CancellationToken.NONE);

        assertTreeEquals(actual, expectedTree);
    }

    private static Stream<Arguments> provideAmounts() {
        return Stream.of(
            Arguments.arguments(1, 4, 1),
            Arguments.arguments(2, 4, 1),
            Arguments.arguments(3, 4, 1),
            Arguments.arguments(4, 4, 1),
            Arguments.arguments(5, 8, 2),
            Arguments.arguments(6, 8, 2)
        );
    }

    @ParameterizedTest
    @MethodSource("provideAmounts")
    void shouldCraftCorrectAmountWhenNotRequestingAMultipleOfThePatternOutputAmount(
        final long requestedAmount,
        final long planksCrafted,
        final long logsUsed
    ) {
        final RootStorage storage = storage(new ResourceAmount(OAK_LOG, 30));
        final PatternRepository patterns = patterns(
            pattern()
                .ingredient(OAK_LOG, 1)
                .output(OAK_PLANKS, 2)
                .output(OAK_PLANKS, 2)
                .output(com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.STICKS, 1)
                .build()
        );

        final TreePreview actual = calculateTree(storage, patterns, OAK_PLANKS, requestedAmount, CancellationToken.NONE);
        final TreePreview expected = tree(PreviewType.SUCCESS, OAK_PLANKS, planksCrafted)
            .node(OAK_LOG, logsUsed).available(logsUsed).end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldCancel() {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 4).build(),
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 2,
            new CancelledCancellationToken());

        assertTreeEquals(actual, new TreePreview(PreviewType.CANCELLED, null, Collections.emptyList()));
    }

    private static TreePreview calculateTree(
        final RootStorage storage,
        final PatternRepository patterns,
        final ResourceKey resource,
        final long requestedAmount,
        final CancellationToken cancellationToken
    ) {
        return CraftingInitializer.solveAndCalculateTreePreview(
            storage,
            patterns,
            resource,
            requestedAmount,
            cancellationToken
        ).previewResult();
    }

    private static void assertTreeEquals(final TreePreview actual, final TreePreview expected) {
        assertThat(actual.type()).isEqualTo(expected.type());
        assertThat(renderTree(normalize(actual.rootNode()))).isEqualTo(renderTree(normalize(expected.rootNode())));
        assertThat(actual.outputsOfPatternWithCycle()).usingRecursiveFieldByFieldElementComparator()
            .containsExactlyInAnyOrderElementsOf(expected.outputsOfPatternWithCycle());
    }

    private static String renderTree(final TreePreviewNode node) {
        if (node == null) {
            return "null";
        }
        final StringBuilder builder = new StringBuilder();
        renderTree(node, builder, 0);
        return builder.toString();
    }

    private static void renderTree(final TreePreviewNode node, final StringBuilder builder, final int depth) {
        builder.append("  ".repeat(depth))
            .append(node.getResource())
            .append(" amount=")
            .append(node.getAmount())
            .append(" toCraft=")
            .append(node.getToCraft())
            .append(" available=")
            .append(node.getAvailable())
            .append(" missing=")
            .append(node.getMissing())
            .append('\n');
        for (final TreePreviewNode child : node.getChildren()) {
            renderTree(child, builder, depth + 1);
        }
    }

    private static TreePreviewNode normalize(final TreePreviewNode node) {
        // Orders children by resource name and normalizes all children recursively, so that tree equality checks
        // are not affected by arbitrary ordering of children in the tree preview.
        if (node == null) {
            return null;
        }
        final List<TreePreviewNode> normalizedChildren = node.getChildren().stream()
            .map(PortedTreePreviewTest::normalize)
            .sorted(Comparator.comparing(child -> child.getResource().toString()))
            .toList();
        return new TreePreviewNode(
            node.getResource(),
            node.getAmount(),
            node.getToCraft(),
            node.getAvailable(),
            node.getMissing(),
            normalizedChildren
        );
    }

    @Test
    void shouldDetectPatternCycles() {
        final RootStorage storage = storage();
        final var cycledPattern = pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 4).build();
        final PatternRepository patterns = patterns(
            cycledPattern,
            pattern().ingredient(OAK_PLANKS, 4).output(OAK_LOG, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, OAK_PLANKS, 1, CancellationToken.NONE);

        final TreePreview expected = tree(PreviewType.MISSING_RESOURCES, OAK_PLANKS, 4)
            .node(OAK_LOG, 1).missing(1).end()
            .build();

        assertTreeEquals(actual, expected);
    }

    @Test
    void shouldDetectNumberOverflowInIngredient() {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, Long.MAX_VALUE).output(OAK_PLANKS, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, OAK_PLANKS, 2, CancellationToken.NONE);
        assertThat(actual).usingRecursiveComparison().isEqualTo(new TreePreview(
            PreviewType.OVERFLOW, null, Collections.emptyList()
        ));
    }

    @Test
    void shouldDetectNumberOverflowWithRootPattern() {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 4).build(),
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, OAK_PLANKS, Long.MAX_VALUE, CancellationToken.NONE);
        assertThat(actual).usingRecursiveComparison().isEqualTo(new TreePreview(
            PreviewType.OVERFLOW, null, Collections.emptyList()
        ));
    }

    @Test
    void shouldDetectNumberOverflowWithOutputOfChildPattern() {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns(
            pattern().ingredient(OAK_LOG, 1).output(OAK_PLANKS, 4).output(SIGN, Long.MAX_VALUE).build(),
            pattern().ingredient(OAK_PLANKS, 4).output(CRAFTING_TABLE, 1).build()
        );

        final TreePreview actual = calculateTree(storage, patterns, CRAFTING_TABLE, 2, CancellationToken.NONE);
        assertThat(actual).usingRecursiveComparison().isEqualTo(new TreePreview(
            PreviewType.OVERFLOW, null, Collections.emptyList()
        ));
    }
}