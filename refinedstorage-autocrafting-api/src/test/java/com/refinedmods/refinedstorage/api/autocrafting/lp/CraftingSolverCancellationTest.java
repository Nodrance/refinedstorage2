package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_PLANKS;
import static org.assertj.core.api.Assertions.assertThat;

class CraftingSolverCancellationTest {
    @Test
    void shouldFallbackToDeficitAnalysisWhenOnlyLoopSnippingTokenIsCancelled() {
        final SanitizedRecipe recipe = new SanitizedRecipe(
            UUID.randomUUID(),
            UUID.randomUUID(),
            pool(OAK_PLANKS, 1),
            pool(OAK_PLANKS, 2),
            0,
            0
        );

        final ResourcePool startingResources = ResourcePool.empty();
        final ResourcePool target = pool(OAK_PLANKS, 1);

        final CraftingSolver sut = new CraftingSolver(CancellationToken.NONE, new CancelledToken());

        final Optional<RecipeApplicationPath> result = sut.solve(List.of(recipe), startingResources, target);

        assertThat(result).isPresent();
        assertThat(result.get().applicationSet().missingResources().getAmount(key(OAK_PLANKS))).isEqualTo(1L);
    }

    private static ResourcePool pool(final ResourceKey resource, final long amount) {
        final ResourcePool pool = ResourcePool.empty();
        pool.setAmount(key(resource), amount);
        return pool;
    }

    private static MultiResourceKey key(final ResourceKey resource) {
        return new MultiResourceKey(List.of(resource));
    }

    private static final class CancelledToken implements CancellationToken {
        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public void cancel() {
            // no-op
        }

        @Override
        public long timeRemainingMillis() {
            return 0L;
        }
    }
}