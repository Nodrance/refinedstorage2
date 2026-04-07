package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import java.util.function.Supplier;

public final class AutocraftingModeContext {
    private static final ThreadLocal<Boolean> USE_LINEAR_AUTOCRAFTING_SYSTEM = new ThreadLocal<>();

    private AutocraftingModeContext() {
    }

    public static boolean isUseLinearAutocraftingSystem() {
        return USE_LINEAR_AUTOCRAFTING_SYSTEM.get() == null || USE_LINEAR_AUTOCRAFTING_SYSTEM.get();
    }

    public static <T> T withUseLinearAutocraftingSystem(final boolean useLinearAutocraftingSystem,
                                                        final Supplier<T> supplier) {
        final Boolean previousValue = USE_LINEAR_AUTOCRAFTING_SYSTEM.get();
        USE_LINEAR_AUTOCRAFTING_SYSTEM.set(useLinearAutocraftingSystem);
        try {
            return supplier.get();
        } finally {
            if (previousValue == null) {
                USE_LINEAR_AUTOCRAFTING_SYSTEM.remove();
            } else {
                USE_LINEAR_AUTOCRAFTING_SYSTEM.set(previousValue);
            }
        }
    }

    public static void withUseLinearAutocraftingSystem(final boolean useLinearAutocraftingSystem,
                                                        final Runnable runnable) {
        withUseLinearAutocraftingSystem(useLinearAutocraftingSystem, () -> {
            runnable.run();
            return null;
        });
    }
}
