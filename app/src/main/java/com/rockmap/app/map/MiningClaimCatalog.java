package com.rockmap.app.map;

/**
 * Shared Alpha 5 mining-claim presentation constants.
 *
 * All BLM MLRS claim cases in the Alpha 5 "not closed" overlay use one high-contrast
 * magenta treatment. Claim type and disposition are shown on tap rather than encoded by
 * multiple colors that could be confused with the land-status palette.
 */
public final class MiningClaimCatalog {
    public static final String COLOR_HEX = "#e600a9";
    public static final String LEGEND_LABEL = "BLM MLRS mining claim case — disposition not closed";

    private MiningClaimCatalog() {}
}
