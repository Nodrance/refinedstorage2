package com.refinedmods.refinedstorage.api.autocrafting.calculation;

public class CraftingCalculatorDisabledException extends CalculationException {
    public CraftingCalculatorDisabledException() {
        super("The default crafting calculator is temporarily disabled");
    }
}