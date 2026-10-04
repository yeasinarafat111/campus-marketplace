package com.campus.marketplace.service;

import com.campus.marketplace.dto.ListingRequest;
import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.dto.ListingSearchCriteria;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.ListingSpecifications;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListingService {

    public static final int MAX_PAGE_SIZE = 100;

    /** Public sort keys → entity properties. Anything else is rejected. */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "price", "price",
            "title", "title");

    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUser;
    private final ImageStorageService imageStorage;
    private final TransactionTemplate transactionTemplate;

    /** Public browse: ACTIVE listings whose owner hasn't been deactivated. */
    @Transactional(readOnly = true)
    public PageResponse<ListingResponse> search(String keyword, Category category, BigDecimal minPrice,
                                                BigDecimal maxPrice, int page, int size, String sort,
                                                String direction) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BadRequestException("minPrice must not be greater than maxPrice");
        }
        var criteria = new ListingSearchCriteria(keyword, category, minPrice, maxPrice, ListingStatus.ACTIVE);
        var spec = ListingSpecifications.matching(criteria).and(ListingSpecifications.ownerActive());
        var result = listingRepository.findAll(spec, pageable(page, size, sort, direction));
        return PageResponse.of(result, ListingResponse::from);
    }

    /**
     * Listings of deactivated owners look like they don't exist (404), except to admins, who need to see
     * them to moderate.
     */
    @Transactional(readOnly = true)
    public ListingResponse get(Long id) {
        Listing listing = findWithOwner(id);
        if (!listing.getOwner().isActive() && !currentUser.isAdmin()) {
            throw notFound(id);
        }
        return ListingResponse.from(listing);
    }

    /** The current user's listings, including sold ones unless a status is given. */
    @Transactional(readOnly = true)
    public PageResponse<ListingResponse> mine(ListingStatus status, int page, int size, String sort, String direction) {
        var criteria = new ListingSearchCriteria(null, null, null, null, status);
        var spec = ListingSpecifications.matching(criteria)
                .and(ListingSpecifications.ownedBy(currentUser.requireId()));
        return PageResponse.of(listingRepository.findAll(spec, pageable(page, size, sort, direction)),
                ListingResponse::from);
    }

    public ListingResponse create(ListingRequest request) {
        return create(request, null);
    }

    /**
     * Creates a listing owned by the current user. If an image is given it is uploaded first and its URL
     * replaces {@code request.imageUrl()}. The upload happens outside the database transaction (so a slow
     * upload doesn't hold a connection), and the image is deleted again if saving the listing fails.
     */
    public ListingResponse create(ListingRequest request, MultipartFile image) {
        Long ownerId = currentUser.requireId();
        ImageStorageService.UploadedImage uploaded =
                image != null && !image.isEmpty() ? imageStorage.upload(image) : null;
        try {
            return transactionTemplate.execute(tx -> {
                User owner = userRepository.findById(ownerId)
                        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                Listing listing = Listing.builder()
                        .owner(owner)
                        .status(ListingStatus.ACTIVE)
                        .build();
                apply(request, listing);
                if (uploaded != null) {
                    listing.setImageUrl(uploaded.secureUrl());
                }
                return ListingResponse.from(listingRepository.save(listing));
            });
        } catch (RuntimeException e) {
            if (uploaded != null) {
                deleteImageQuietly(uploaded.publicId());
            }
            throw e;
        }
    }

    /** Full replacement of the editable fields. Owner only (admins can delete, not edit). */
    @Transactional
    public ListingResponse update(Long id, ListingRequest request) {
        Listing listing = findWithOwner(id);
        requireOwner(listing, "You can only edit your own listings");
        apply(request, listing);
        return ListingResponse.from(listing);
    }

    /** Owner only. Idempotent: marking an already-sold listing returns it unchanged. */
    @Transactional
    public ListingResponse markSold(Long id) {
        Listing listing = findWithOwner(id);
        requireOwner(listing, "You can only mark your own listings as sold");
        listing.setStatus(ListingStatus.SOLD);
        return ListingResponse.from(listing);
    }

    /** Owner or ADMIN. */
    @Transactional
    public void delete(Long id) {
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        if (!isOwner(listing) && !currentUser.isAdmin()) {
            throw new ForbiddenException("You can only delete your own listings");
        }
        listingRepository.delete(listing);
    }

    private void deleteImageQuietly(String publicId) {
        try {
            imageStorage.delete(publicId);
        } catch (RuntimeException e) {
            log.warn("Could not clean up orphaned image {}: {}", publicId, e.getMessage());
        }
    }

    private Listing findWithOwner(Long id) {
        return listingRepository.findWithOwnerById(id).orElseThrow(() -> notFound(id));
    }

    private void requireOwner(Listing listing, String message) {
        if (!isOwner(listing)) {
            throw new ForbiddenException(message);
        }
    }

    private boolean isOwner(Listing listing) {
        // getId() on a lazy proxy doesn't trigger a query.
        return Objects.equals(listing.getOwner().getId(), currentUser.requireId());
    }

    private static void apply(ListingRequest request, Listing listing) {
        listing.setTitle(request.title());
        listing.setDescription(request.description());
        listing.setPrice(request.price());
        listing.setCategory(request.category());
        listing.setImageUrl(request.imageUrl());
    }

    private static Pageable pageable(int page, int size, String sort, String direction) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String property = SORT_FIELDS.get(sort);
        if (property == null) {
            throw new BadRequestException("sort must be one of: " + String.join(", ", SORT_FIELDS.keySet().stream().sorted().toList()));
        }
        Sort.Direction dir = switch (direction.toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new BadRequestException("direction must be asc or desc");
        };
        // Tie-break on id so pages are stable when many rows share the same price/date.
        return PageRequest.of(page, size, Sort.by(dir, property).and(Sort.by(dir, "id")));
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Listing " + id + " not found");
    }
}
