package com.campus.marketplace.config;

/** Names of the caches (see {@link CacheConfig} for what each holds and how long). */
public final class CacheNames {

    public static final String LISTING_BY_ID = "listingById";
    public static final String FEATURED_LISTINGS = "featuredListings";
    public static final String CATEGORY_COUNTS = "categoryCounts";
    public static final String USER_PROFILE = "userProfile";
    public static final String FEE_QUOTE = "feeQuote";
    public static final String ASSISTANT_ANSWERS = "assistantAnswers";

    private CacheNames() {
    }
}
