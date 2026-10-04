package com.campus.marketplace.service;

import com.campus.marketplace.config.CloudinaryProperties;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ImageUploadException;
import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudinaryImageStorageServiceTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    static final byte[] GIF = "GIF89a....".getBytes(StandardCharsets.US_ASCII);
    static final byte[] WEBP = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);

    private final Cloudinary cloudinary = mock(Cloudinary.class);
    private final Uploader uploader = mock(Uploader.class);
    private CloudinaryImageStorageService service;

    @BeforeEach
    void setUp() {
        when(cloudinary.uploader()).thenReturn(uploader);
        service = new CloudinaryImageStorageService(cloudinary, new CloudinaryProperties("cloud", "key", "secret", "listings"));
    }

    @Test
    void upload_png_sendsBytesToFolder_andReturnsSecureUrl() throws IOException {
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of(
                "secure_url", "https://res.cloudinary.com/cloud/image/upload/v1/listings/abc.png",
                "public_id", "listings/abc"));

        var result = service.upload(new MockMultipartFile("image", "lamp.png", "image/png", PNG));

        assertThat(result.secureUrl()).isEqualTo("https://res.cloudinary.com/cloud/image/upload/v1/listings/abc.png");
        assertThat(result.publicId()).isEqualTo("listings/abc");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> options = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(eq(PNG), options.capture());
        assertThat(options.getValue())
                .containsEntry("folder", "listings")
                .containsEntry("resource_type", "image")
                .containsEntry("overwrite", false);
    }

    @Test
    void allowedFormats_areDetectedFromBytes() {
        assertThat(CloudinaryImageStorageService.isAllowedImage(PNG)).isTrue();
        assertThat(CloudinaryImageStorageService.isAllowedImage(JPEG)).isTrue();
        assertThat(CloudinaryImageStorageService.isAllowedImage(GIF)).isTrue();
        assertThat(CloudinaryImageStorageService.isAllowedImage(WEBP)).isTrue();
        assertThat(CloudinaryImageStorageService.isAllowedImage(new byte[] {(byte) 0xFF})).isFalse();
    }

    @Test
    void textFileClaimingToBePng_isRejectedWithoutUploading() throws IOException {
        var fake = new MockMultipartFile("image", "evil.png", "image/png", "<?php echo 1; ?>".getBytes());

        assertThatThrownBy(() -> service.upload(fake))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(CloudinaryImageStorageService.INVALID_TYPE_MESSAGE);
        verify(uploader, never()).upload(any(), anyMap());
    }

    @Test
    void svg_isRejected() {
        var svg = new MockMultipartFile("image", "x.svg", "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes());

        assertThatThrownBy(() -> service.upload(svg)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void emptyFile_isRejected() {
        assertThatThrownBy(() -> service.upload(new MockMultipartFile("image", "a.png", "image/png", new byte[0])))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Image file is empty");
    }

    @Test
    void missingCredentials_returns503WithoutCallingCloudinary() throws IOException {
        var unconfigured = new CloudinaryImageStorageService(cloudinary, new CloudinaryProperties("", "", "", null));

        assertThatThrownBy(() -> unconfigured.upload(new MockMultipartFile("image", "a.png", "image/png", PNG)))
                .isInstanceOfSatisfying(ImageUploadException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(uploader, never()).upload(any(), anyMap());
    }

    @Test
    void cloudinaryFailure_becomes502() throws IOException {
        when(uploader.upload(any(), anyMap())).thenThrow(new IOException("Invalid Signature"));

        assertThatThrownBy(() -> service.upload(new MockMultipartFile("image", "a.png", "image/png", PNG)))
                .isInstanceOfSatisfying(ImageUploadException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void delete_callsDestroy() throws IOException {
        service.delete("listings/abc");

        verify(uploader).destroy(eq("listings/abc"), anyMap());
    }
}
