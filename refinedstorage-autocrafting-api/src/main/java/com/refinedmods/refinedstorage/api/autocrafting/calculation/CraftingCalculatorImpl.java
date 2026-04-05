package com.refinedmods.refinedstorage.api.autocrafting.calculation;

import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.core.CoreValidations;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

public class CraftingCalculatorImpl implements CraftingCalculator {
    public CraftingCalculatorImpl(final PatternRepository patternRepository, final RootStorage rootStorage) {
        CoreValidations.validateNotNull(patternRepository, "Pattern repository cannot be null");
        CoreValidations.validateNotNull(rootStorage, "Root storage cannot be null");
    }

    @Override
    public <T> void calculate(final ResourceKey resource,
                              final long amount,
                              final CraftingCalculatorListener<T> listener,
                              final CancellationToken cancellationToken) throws CancellationException {
        throw new CraftingCalculatorDisabledException();
    }
}
