package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.List;

public record LpStepPlan(List<LpExecutionPlanStep> steps, boolean hasRecipeCycles) {
    public LpStepPlan {
        steps = List.copyOf(steps);
    }
}
