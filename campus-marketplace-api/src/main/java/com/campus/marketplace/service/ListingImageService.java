package com.campus.marketplace.service;

import com.campus.marketplace.config.CacheNames;
import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.dto.ReorderImagesRequest;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingImage;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A listing's photos: up to five, the first is the cover. Owner only. Adding uploads to Cloudinary first (outside
 * any transaction, so a slow upload doesn't hold a connection) and removes the file again if saving fails; removing
 * a photo deletes the file from Cloudinary once the change has committed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingImageService {

    public static final int MAX_IMAGES = 5;

    private final ListingRepository listingRepository;
    private final ImageStorageService imageStorage;
    private final CurrentUserProvider currentUser;
    private final TransactionTemplate transactionTemplate;

    private static final String[] NO_KEYS = {};

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#listingId"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true)})
    public ListingResponse add(Long listingId, MultipartFile file) {
        Long userId = currentUser.requireId();
        // Cheap checks first, so a stranger or a full listing never causes an upload.
        transactionTemplate.executeWithoutResult(tx -> requireRoom(requireOwned(listingRepository.findById(listingId)
                .orElseThrow(() -> notFound(listingId)), userId)));
        ImageStorageService.UploadedImage uploaded = imageStorage.upload(file);
        try {
            return transactionTemplate.execute(tx -> {
                Listing listing = listingRepository.findForUpdateById(listingId).orElseThrow(() -> notFound(listingId));
                requireRoom(requireOwned(listing, userId));
                // A listing from before photos had its own table has only imageUrl: keep that cover as photo one.
                if (listing.getImages().isEmpty() && listing.getImageUrl() != null) {
                    listing.addImage(listing.getImageUrl(), null);
                }
                listing.addImage(uploaded.secureUrl(), uploaded.publicId());
                listing.syncCover();
                // Flush so the new photo has its id before the response is built.
                listingRepository.flush();
                return ListingResponse.from(listing, userId);
            });
        } catch (RuntimeException e) {
            deleteQuietly(uploaded.publicId());
            throw e;
        }
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#listingId"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true)})
    public ListingResponse remove(Long listingId, Long imageId) {
        Long userId = currentUser.requireId();
        List<String> toDelete = new ArrayList<>();
        ListingResponse response = transactionTemplate.execute(tx -> {
            Listing listing = listingRepository.findForUpdateById(listingId).orElseThrow(() -> notFound(listingId));
            requireOwned(listing, userId);
            ListingImage image = listing.getImages().stream().filter(i -> Objects.equals(i.getId(), imageId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Photo " + imageId + " not found on this listing"));
            listing.getImages().remove(image);
            if (image.getPublicId() != null) {
                toDelete.add(image.getPublicId());
            }
            if (listing.getImages().isEmpty()) {
                listing.setImageUrl(null);
            } else {
                listing.syncCover();
            }
            return ListingResponse.from(listing, userId);
        });
        toDelete.forEach(this::deleteQuietly);
        return response;
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#listingId"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true)})
    public ListingResponse reorder(Long listingId, ReorderImagesRequest request) {
        Long userId = currentUser.requireId();
        return transactionTemplate.execute(tx -> {
            Listing listing = listingRepository.findForUpdateById(listingId).orElseThrow(() -> notFound(listingId));
            requireOwned(listing, userId);
            List<Long> wanted = request.imageIds();
            var current = new HashSet<>(listing.getImages().stream().map(ListingImage::getId).toList());
            if (wanted.size() != current.size() || !current.equals(new HashSet<>(wanted))) {
                throw new BadRequestException("imageIds must list every photo of this listing exactly once");
            }
            List<ListingImage> ordered = wanted.stream()
                    .map(id -> listing.getImages().stream().filter(i -> i.getId().equals(id)).findFirst().orElseThrow()).toList();
            listing.getImages().clear();
            listing.getImages().addAll(ordered);
            listing.syncCover();
            return ListingResponse.from(listing, userId);
        });
    }

    /** Deletes the photos' files after the surrounding transaction commits (or now, outside one). Never throws. */
    public void deleteFilesAfterCommit(List<String> publicIds) {
        if (publicIds.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publicIds.forEach(ListingImageService.this::deleteQuietly);
                }
            });
        } else {
            publicIds.forEach(this::deleteQuietly);
        }
    }

    private Listing requireOwned(Listing listing, Long userId) {
        if (!Objects.equals(listing.getOwner().getId(), userId)) {
            throw new ForbiddenException("You can only change the photos of your own listings");
        }
        return listing;
    }

    private void requireRoom(Listing listing) {
        if (listing.getImages().size() >= MAX_IMAGES) {
            throw new ConflictException("A listing can have at most " + MAX_IMAGES + " photos");
        }
    }

    private void deleteQuietly(String publicId) {
        try {
            imageStorage.delete(publicId);
        } catch (RuntimeException e) {
            log.warn("Could not delete image {}: {}", publicId, e.getMessage());
        }
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Listing " + id + " not found");
    }
}
