package com.campus.marketplace.dto;

import java.math.BigDecimal;

/**
 * What posting a listing at a given price costs, computed on the server for the create page's live line
 * ("Posting fee: $0.60 (3%), paid after you submit"). When {@code applies} is false the listing posts for free
 * and the other numbers are zero.
 *
 * @param thresholdCents prices up to and including this are free to post
 */
public record PostingFeeQuote(boolean applies, long feeCents, BigDecimal rate, long thresholdCents, String currency) {
}
