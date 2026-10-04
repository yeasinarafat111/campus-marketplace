package com.campus.marketplace.service;

import org.springframework.web.multipart.MultipartFile;

public interface ImageStorageService {

    /**
     * Validates and uploads an image.
     *
     * @return the public HTTPS URL and the provider id (needed to delete it again)
     * @throws com.campus.marketplace.exception.BadRequestException if the file is empty or not an allowed image
     * @throws com.campus.marketplace.exception.ImageUploadException if the provider is unreachable or rejects it
     */
    UploadedImage upload(MultipartFile file);

    /** Deletes a previously uploaded image. */
    void delete(String publicId);

    record UploadedImage(String secureUrl, String publicId) {
    }
}
