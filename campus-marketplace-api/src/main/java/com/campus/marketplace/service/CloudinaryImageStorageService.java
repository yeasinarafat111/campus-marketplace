package com.campus.marketplace.service;

import com.campus.marketplace.config.CloudinaryProperties;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ImageUploadException;
import com.cloudinary.Cloudinary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Uploads listing images to Cloudinary. The file's type is checked from its first bytes rather than
 * the client-supplied Content-Type, and SVG is not accepted (it can carry scripts).
 * The size limit is enforced earlier by spring.servlet.multipart.max-file-size (413).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryImageStorageService implements ImageStorageService {

    static final String INVALID_TYPE_MESSAGE = "Only JPEG, PNG, WEBP or GIF images are allowed";

    private final Cloudinary cloudinary;
    private final CloudinaryProperties properties;

    @Override
    public UploadedImage upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Image file is empty");
        }
        byte[] bytes = readBytes(file);
        if (!isAllowedImage(bytes)) {
            throw new BadRequestException(INVALID_TYPE_MESSAGE);
        }
        if (!properties.isConfigured()) {
            throw new ImageUploadException("Image upload is not available right now", HttpStatus.SERVICE_UNAVAILABLE, null);
        }

        Map<String, Object> options = new HashMap<>();
        options.put("resource_type", "image");
        options.put("unique_filename", true);
        options.put("overwrite", false);
        if (properties.folder() != null && !properties.folder().isBlank()) {
            options.put("folder", properties.folder());
        }

        try {
            Map<?, ?> result = cloudinary.uploader().upload(bytes, options);
            Object url = result.get("secure_url");
            Object publicId = result.get("public_id");
            if (url == null || publicId == null) {
                throw new IllegalStateException("Cloudinary response missing secure_url/public_id");
            }
            log.info("Uploaded image to Cloudinary: {}", publicId);
            return new UploadedImage(url.toString(), publicId.toString());
        } catch (IOException | RuntimeException e) {
            log.error("Cloudinary upload failed: {}", e.getMessage());
            throw new ImageUploadException("Image upload failed. Please try again.", HttpStatus.BAD_GATEWAY, e);
        }
    }

    @Override
    public void delete(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, Map.of("resource_type", "image"));
            log.info("Deleted image from Cloudinary: {}", publicId);
        } catch (IOException | RuntimeException e) {
            log.error("Cloudinary delete failed for {}: {}", publicId, e.getMessage());
            throw new ImageUploadException("Image delete failed", HttpStatus.BAD_GATEWAY, e);
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Could not read the uploaded file");
        }
    }

    /** JPEG, PNG, GIF or WEBP, by file signature. */
    static boolean isAllowedImage(byte[] b) {
        return startsWith(b, 0xFF, 0xD8, 0xFF)                                   // JPEG
                || startsWith(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)    // PNG
                || startsWithAscii(b, 0, "GIF87a") || startsWithAscii(b, 0, "GIF89a")
                || (startsWithAscii(b, 0, "RIFF") && startsWithAscii(b, 8, "WEBP"));
    }

    private static boolean startsWith(byte[] b, int... signature) {
        if (b.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((b[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] b, int offset, String text) {
        byte[] expected = text.getBytes(StandardCharsets.US_ASCII);
        return b.length >= offset + expected.length
                && Arrays.equals(b, offset, offset + expected.length, expected, 0, expected.length);
    }
}
