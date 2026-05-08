package com.refinedmods.refinedstorage.api.autocrafting.task;

public enum PatternStepResult {
    COMPLETED,
    RUNNING,
    IDLE;

    boolean isChanged() {
        return this != IDLE;
    }
}
