package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.CancelledCancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.CraftingInitializer;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.patterns;
import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.storage;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.CRAFTING_TABLE;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_LOG;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_PLANKS;
import static org.assertj.core.api.Assertions.assertThat;

class PortedCraftabilityTest {
    @Test
    void shouldNotFindMaxAmountIfThereAreAlwaysMissingResources() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_PLANKS, 1)
        );
        final PatternRepository patterns = patterns(
            pattern()
                .ingredient(OAK_LOG, 1)
                .output(OAK_PLANKS, 4)
                .build(),
            pattern()
                .ingredient(OAK_PLANKS, 4)
                .output(CRAFTING_TABLE, 1)
                .build()
        );

        final long maxAmount = CraftingInitializer.findMaxCraftableAmount(
            storage,
            patterns,
            CRAFTING_TABLE,
            Long.MAX_VALUE,
            CancellationToken.NONE
        );

        assertThat(maxAmount).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 64L, 128L})
    void shouldFindMaxAmount(final long amountPossible) {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_LOG, amountPossible)
        );
        final PatternRepository patterns = patterns(
            pattern()
                .ingredient(OAK_LOG, 1)
                .output(OAK_PLANKS, 4)
                .build(),
            pattern()
                .ingredient(OAK_PLANKS, 4)
                .output(CRAFTING_TABLE, 1)
                .build()
        );

        final long maxAmount = CraftingInitializer.findMaxCraftableAmount(
            storage,
            patterns,
            CRAFTING_TABLE,
            Long.MAX_VALUE,
            CancellationToken.NONE
        );

        assertThat(maxAmount).isEqualTo(amountPossible);
    }

    @Test
    void shouldNotFindMaxAmountIfCancelled() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_LOG, 1)
        );
        final PatternRepository patterns = patterns(
            pattern()
                .ingredient(OAK_LOG, 1)
                .output(OAK_PLANKS, 4)
                .build(),
            pattern()
                .ingredient(OAK_PLANKS, 4)
                .output(CRAFTING_TABLE, 1)
                .build()
        );

        final long maxAmount = CraftingInitializer.findMaxCraftableAmount(
            storage,
            patterns,
            CRAFTING_TABLE,
            Long.MAX_VALUE,
            new CancelledCancellationToken()
        );

        assertThat(maxAmount).isZero();
    }

    @Test
    void shouldNotFindMaxAmountIfThereIsANumberOverflow() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_PLANKS, Long.MAX_VALUE)
        );
        final PatternRepository patterns = patterns(
            pattern()
                .ingredient(OAK_LOG, 1)
                .output(OAK_PLANKS, 4)
                .build(),
            pattern()
                .ingredient(OAK_PLANKS, 4)
                .output(CRAFTING_TABLE, 1)
                .build()
        );

        final long maxAmount = CraftingInitializer.findMaxCraftableAmount(
            storage,
            patterns,
            CRAFTING_TABLE,
            Long.MAX_VALUE,
            CancellationToken.NONE
        );

        assertThat(maxAmount).isZero();
    }
}