package com.campus.marketplace.service;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** The photo is fetched server-side, so only Cloudinary over HTTPS is allowed (no probing internal addresses). */
class ListingPhotoFetcherTest {

    @Test
    void cloudinaryImages_areFetchedResized() {
        assertThat(ListingPhotoFetcher.allowedUri("https://res.cloudinary.com/demo/image/upload/v1/folder/abc.png"))
                .contains(URI.create("https://res.cloudinary.com/demo/image/upload/w_1024,c_limit,q_auto,f_jpg/v1/folder/abc.png"));
    }

    @Test
    void everythingElse_isSkipped() {
        assertThat(ListingPhotoFetcher.allowedUri(null)).isEmpty();
        assertThat(ListingPhotoFetcher.allowedUri("http://res.cloudinary.com/demo/image/upload/a.png")).isEmpty();
        assertThat(ListingPhotoFetcher.allowedUri("https://metadata.google.internal/computeMetadata/v1/")).isEmpty();
        assertThat(ListingPhotoFetcher.allowedUri("https://169.254.169.254/latest")).isEmpty();
        assertThat(ListingPhotoFetcher.allowedUri("https://res.cloudinary.com.evil.example/a.png")).isEmpty();
        assertThat(ListingPhotoFetcher.allowedUri("https://user@res.cloudinary.com/a.png")).isEmpty();
        assertThat(ListingPhotoFetcher.allowedUri("https://res.cloudinary.com:8443/a.png")).isEmpty();
        assertThat(ListingPhotoFetcher.allowedUri("file:///etc/passwd")).isEmpty();
        assertThat(ListingPhotoFetcher.allowedUri("not a url")).isEmpty();
    }
}
