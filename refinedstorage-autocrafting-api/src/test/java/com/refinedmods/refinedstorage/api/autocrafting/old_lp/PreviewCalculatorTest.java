package com.refinedmods.refinedstorage.api.autocrafting.lp;

import org.junit.jupiter.api.Test;

import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.patterns;
import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.storage;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_PLANKS;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.SPRUCE_PLANKS;

import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewBuilder;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import static org.assertj.core.api.Assertions.assertThat;

public class PreviewCalculatorTest {
    @Test
    void shouldCalculateMissingResourcesWhenLoopHasNoEntrance() {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns(
            pattern()
                .ingredient(OAK_PLANKS, 1)
                .output(OAK_PLANKS, 2)
                .build()
        );

        final Preview preview = calculatePreview(storage, patterns, OAK_PLANKS, 1, CancellationToken.NONE);

        System.out.println("Actual preview: " + preview);

        assertPreviewEquals(preview, PreviewBuilder.create()
            .addMissing(OAK_PLANKS, 1)
            .build());
    }

    @Test 
    void shouldCalculateMissingResourcesWhenLoopIsMissingExternalResources() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_PLANKS, 1),
            new ResourceAmount(SPRUCE_PLANKS, 2)
        );
        final PatternRepository patterns = patterns(
            pattern()
                .ingredient(OAK_PLANKS, 1)
                .ingredient(SPRUCE_PLANKS, 1)
                .output(OAK_PLANKS, 2)
                .build()
        );

        final Preview preview = calculatePreview(storage, patterns, OAK_PLANKS, 5, CancellationToken.NONE);

        System.out.println("Actual preview 2: " + preview);

        assertPreviewEquals(preview, PreviewBuilder.create()
            .addToCraft(OAK_PLANKS, 10)
            .addAvailable(OAK_PLANKS, 1)
            .addAvailable(SPRUCE_PLANKS, 2)
            .addMissing(SPRUCE_PLANKS, 3)
            .build());
    }

    private static Preview calculatePreview(
        final RootStorage storage,
        final PatternRepository patterns,
        final ResourceKey resource,
        final long requestedAmount,
        final CancellationToken cancellationToken
    ) {
        return CraftingOrchestrator.solveAndCalculatePreview(
            storage,
            patterns,
            resource,
            requestedAmount,
            cancellationToken
        ).previewResult();
    }

    private static void assertPreviewEquals(final Preview actual, final Preview expected) {
        System.out.println("Actual type: " + actual.type());
        System.out.println("Expected type: " + expected.type());
        System.out.println("Actual items: " + actual.items());
        System.out.println("Expected items: " + expected.items());
        assertThat(actual.type()).isEqualTo(expected.type());
        assertThat(actual.items())
            .usingRecursiveFieldByFieldElementComparator()
            .containsExactlyInAnyOrderElementsOf(expected.items());
        assertThat(actual.outputsOfPatternWithCycle())
            .usingRecursiveFieldByFieldElementComparator()
            .containsExactlyInAnyOrderElementsOf(expected.outputsOfPatternWithCycle());
    }
}
