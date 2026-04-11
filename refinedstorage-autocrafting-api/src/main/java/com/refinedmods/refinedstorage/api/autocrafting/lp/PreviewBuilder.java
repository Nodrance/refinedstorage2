package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

public final class PreviewBuilder {
    private PreviewBuilder() {
    }

    public static Preview buildPreview(final Optional<RecipeApplicationPath> recipeApplicationPath,
                                       final CancellationToken cancellationToken) {
        Objects.requireNonNull(recipeApplicationPath, "recipeApplicationPath cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");

        if (cancellationToken.isCancelled()) {
            return new Preview(PreviewType.CANCELLED, Collections.emptyList(), Collections.emptyList());
        }

        return recipeApplicationPath
            .map(path -> PreviewCalculator.calculatePreview(path, cancellationToken))
            .orElseGet(() -> new Preview(PreviewType.NOT_AVAILABLE, Collections.emptyList(), Collections.emptyList()));
    }

    public static TreePreview buildTreePreview(final Optional<RecipeApplicationPath> recipeApplicationPath,
                                               final CancellationToken cancellationToken) {
        Objects.requireNonNull(recipeApplicationPath, "recipeApplicationPath cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");

        if (cancellationToken.isCancelled()) {
            return new TreePreview(PreviewType.CANCELLED, null, Collections.emptyList());
        }

        return new TreePreview(PreviewType.NOT_AVAILABLE, null, Collections.emptyList());
    }
}
