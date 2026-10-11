package com.campus.marketplace.service;

import com.campus.marketplace.config.AssistantProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Downloads a listing's cover photo for the assistant. Only from Cloudinary over HTTPS: a listing's image URL can
 * be set by its seller, and fetching arbitrary URLs from the server would let them probe internal addresses.
 * Anything that goes wrong (other host, too big, not an image, slow) gives empty and the call goes text-only.
 */
@Slf4j
@Component
public class ListingPhotoFetcher {

    static final String ALLOWED_HOST = "res.cloudinary.com";
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");
    /** A Cloudinary transformation: at most 1024 px wide, JPEG. Smaller to send, and enough to recognise a product. */
    private static final String RESIZE = "w_1024,c_limit,q_auto,f_jpg/";

    private final AssistantProperties properties;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ListingPhotoFetcher(AssistantProperties properties) {
        this.properties = properties;
    }

    public Optional<AssistantGateway.Image> fetch(String imageUrl) {
        Optional<URI> uri = allowedUri(imageUrl);
        if (uri.isEmpty()) {
            return Optional.empty();
        }
        try {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(uri.get()).timeout(Duration.ofSeconds(6)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                String type = response.headers().firstValue("Content-Type").orElse("")
                        .split(";")[0].trim().toLowerCase(Locale.ROOT);
                if (response.statusCode() != 200 || !IMAGE_TYPES.contains(type)) {
                    log.info("Assistant photo skipped: HTTP {} {}", response.statusCode(), type);
                    return Optional.empty();
                }
                byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 1, properties.maxImageBytes() + 1));
                if (data.length > properties.maxImageBytes()) {
                    log.info("Assistant photo skipped: larger than {} bytes", properties.maxImageBytes());
                    return Optional.empty();
                }
                return Optional.of(new AssistantGateway.Image(type, data));
            }
        } catch (IOException e) {
            log.info("Assistant photo skipped: {}", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The URL to fetch, resized by Cloudinary, or empty if it isn't a Cloudinary HTTPS image. */
    static Optional<URI> allowedUri(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(imageUrl.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !ALLOWED_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 || uri.getUserInfo() != null) {
                return Optional.empty();
            }
            String url = uri.toString();
            int upload = url.indexOf("/image/upload/");
            if (upload >= 0) {
                int at = upload + "/image/upload/".length();
                url = url.substring(0, at) + RESIZE + url.substring(at);
            }
            return Optional.of(URI.create(url));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
