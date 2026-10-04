package com.campus.marketplace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.cloudinary.*} (env vars CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY,
 * CLOUDINARY_API_SECRET, CLOUDINARY_FOLDER). Left optional so the app still starts without them;
 * image uploads then fail with 503 until they are set.
 *
 * @param folder Cloudinary folder uploads go into (optional)
 */
@ConfigurationProperties(prefix = "app.cloudinary")
public record CloudinaryProperties(String cloudName, String apiKey, String apiSecret, String folder) {

    public boolean isConfigured() {
        return hasText(cloudName) && hasText(apiKey) && hasText(apiSecret);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
