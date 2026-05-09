package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutocraftingModeContextTest {
    @Test
    void shouldDefaultToLinearAutocraftingWhenUnset() {
        assertThat(AutocraftingModeContext.isUseLinearAutocraftingSystem()).isTrue();
    }

    @Test
    void shouldRestoreDefaultLinearAutocraftingAfterOverride() {
        final boolean result = AutocraftingModeContext.withUseLinearAutocraftingSystem(false, () -> {
            assertThat(AutocraftingModeContext.isUseLinearAutocraftingSystem()).isFalse();
            return AutocraftingModeContext.isUseLinearAutocraftingSystem();
        });

        assertThat(result).isFalse();
        assertThat(AutocraftingModeContext.isUseLinearAutocraftingSystem()).isTrue();
    }
}