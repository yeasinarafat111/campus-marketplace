package com.campus.marketplace.service;

import com.campus.marketplace.dto.ListingRequest;
import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.dto.ListingSearchCriteria;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.ItemCondition;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.config.CacheNames;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.ListingSpecifications;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import com.campus.marketplace.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
    private final PaymentService paymentService;
    private final PublicListingReader publicReader;
    private final PostingFeePolicy postingFeePolicy;
    private final ListingImageService imageService;
    private final ListingCacheEvictor cacheEvictor;

    /**
     * Public browse: ACTIVE listings whose owner hasn't been deactivated. Without an explicit {@code sort},
     * currently featured listings come first, then newest. An explicit sort is honoured exactly.
     *
     * @param ownerId  only this seller's listings, or null
     * @param featured true to return only currently featured listings, or null
     */
    @Transactional(readOnly = true)
    public PageResponse<ListingResponse> search(String keyword, Category category, BigDecimal minPrice,
                                                BigDecimal maxPrice, Long ownerId, Boolean featured, ItemCondition condition, int page,
                                                int size, String sort, String direction) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BadRequestException("minPrice must not be greater than maxPrice");
        }
        Instant now = Instant.now();
        var criteria = new ListingSearchCriteria(keyword, category, minPrice, maxPrice, ListingStatus.ACTIVE);
        var spec = ListingSpecifications.matching(criteria).and(ListingSpecifications.ownerActive());
        if (ownerId != null) {
            spec = spec.and(ListingSpecifications.ownedBy(ownerId));
        }
        if (Boolean.TRUE.equals(featured)) {
            spec = spec.and(ListingSpecifications.featuredAt(now));
        }
        if (condition != null) {
            spec = spec.and(ListingSpecifications.hasCondition(condition));
        }
        Pageable pageable;
        if (sort == null) {
            validatePaging(page, size);
            parseDirection(direction); // not used for the default order, but still rejected if malformed
            spec = spec.and(ListingSpecifications.featuredFirstThenNewest(now));
            pageable = PageRequest.of(page, size);
        } else {
            pageable = pageable(page, size, sort, direction);
        }
        return PageResponse.of(listingRepository.findAll(spec, pageable), ListingResponse::from);
    }

    /**
     * One listing as the caller may see it. The public part comes from the cache; what depends on who is
     * asking (reservedByMe, the deadline, the other side's phone) is added afterwards, and only when the caller
     * could be a party: an anonymous visitor, or anyone looking at a listing that is neither reserved nor sold,
     * gets the cached copy as it is. Listings of deactivated owners look like they don't exist (404), except to
     * admins, who need to see them to moderate, so admins skip the cache.
     */
    public ListingResponse get(Long id) {
        Long viewerId = currentUser.find().map(UserPrincipal::getId).orElse(null);
        if (currentUser.isAdmin()) {
            return transactionTemplate.execute(tx -> ListingResponse.from(findWithOwner(id), viewerId));
        }
        ListingResponse publicView;
        try {
            publicView = publicReader.publicView(id);
        } catch (ResourceNotFoundException e) {
            // An unpublished listing is hidden from everyone but its owner, who can still open it.
            return viewerId == null ? rethrow(e) : transactionTemplate.execute(tx -> {
                Listing own = listingRepository.findWithOwnerById(id)
                        .filter(l -> l.getStatus() == ListingStatus.PENDING_PAYMENT && l.getOwner().getId().equals(viewerId))
                        .orElseThrow(() -> e);
                return ListingResponse.from(own, viewerId);
            });
        }
        if (viewerId == null || (!publicView.reserved() && publicView.status() != ListingStatus.SOLD)) {
            return publicView;
        }
        return transactionTemplate.execute(tx -> {
            Listing listing = listingRepository.findWithPartiesById(id).orElseThrow(() -> notFound(id));
            ListingResponse response = ListingResponse.from(listing, viewerId);
            // The balance is only for the two sides of a live reservation.
            boolean party = response.reservedByMe() || response.counterparty() != null && listing.isReserved();
            return party ? response.withBalanceDue(paymentService.balanceDue(listing)) : response;
        });
    }

    /** The current user's listings, including sold ones unless a status is given. */
    @Transactional(readOnly = true)
    public PageResponse<ListingResponse> mine(ListingStatus status, Boolean reserved, int page, int size, String sort,
                                              String direction) {
        var criteria = new ListingSearchCriteria(null, null, null, null, status);
        var spec = ListingSpecifications.matching(criteria)
                .and(ListingSpecifications.ownedBy(currentUser.requireId()));
        if (reserved != null) {
            spec = spec.and(ListingSpecifications.reserved(reserved));
        }
        return PageResponse.of(listingRepository.findAll(spec, pageable(page, size, sort, direction)),
                ListingResponse::from);
    }

    // Annotated here too: this overload is what the controller calls, and its call to the two-argument one is a
    // call on `this`, which bypasses the cache proxy.
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    public ListingResponse create(ListingRequest request) {
        return create(request, List.of());
    }

    /**
     * Creates a listing owned by the current user. If an image is given it is uploaded first and its URL
     * replaces {@code request.imageUrl()}. The upload happens outside the database transaction (so a slow
     * upload doesn't hold a connection), and the image is deleted again if saving the listing fails.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    public ListingResponse create(ListingRequest request, List<MultipartFile> images) {
        Long ownerId = currentUser.requireId();
        List<MultipartFile> files = images == null ? List.of() : images.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (files.size() > ListingImageService.MAX_IMAGES) {
            throw new BadRequestException("A listing can have at most " + ListingImageService.MAX_IMAGES + " photos");
        }
        List<ImageStorageService.UploadedImage> uploaded = new java.util.ArrayList<>();
        try {
            for (MultipartFile file : files) {
                uploaded.add(imageStorage.upload(file));
            }
            return transactionTemplate.execute(tx -> {
                User owner = userRepository.findById(ownerId)
                        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                // Above the threshold the listing waits, unpublished, for its posting fee; otherwise it goes live.
                boolean needsFee = postingFeePolicy.applies(request.price());
                Listing listing = Listing.builder()
                        .owner(owner)
                        .status(needsFee ? ListingStatus.PENDING_PAYMENT : ListingStatus.ACTIVE)
                        .postingFeeBasisCents(needsFee ? null : postingFeePolicy.coveredCentsWithoutFee())
                        .build();
                apply(request, listing);
                // Uploaded photos win over a plain URL; a URL alone becomes the single photo.
                for (ImageStorageService.UploadedImage u : uploaded) {
                    listing.addImage(u.secureUrl(), u.publicId());
                }
                if (uploaded.isEmpty() && request.imageUrl() != null) {
                    listing.addImage(request.imageUrl(), null);
                }
                listing.syncCover();
                return ListingResponse.from(listingRepository.save(listing));
            });
        } catch (RuntimeException e) {
            uploaded.forEach(u -> deleteImageQuietly(u.publicId()));
            throw e;
        }
    }

    /**
     * Full replacement of the editable fields. Owner only (admins can delete, not edit). The price can't
     * change while the item is reserved: the buyer's deposit and the balance they owe at Buy were computed
     * from it. The row is locked so a reservation landing at the same moment (the webhook locks it too)
     * can't slip in between this check and the save.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.FEE_QUOTE, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    @Transactional
    public ListingResponse update(Long id, ListingRequest request) {
        Listing listing = listingRepository.findForUpdateById(id).orElseThrow(() -> notFound(id));
        requireOwner(listing, "You can only edit your own listings");
        if (listing.isReserved() && request.price().compareTo(listing.getPrice()) != 0) {
            throw new ConflictException("Price can't change while the item is reserved");
        }
        // The posting fee covers a price. Lowering it is fine; raising it past what was paid for would dodge the
        // fee, so the owner has to post again. A published listing from before posting fees has no limit.
        if (listing.getStatus() != ListingStatus.PENDING_PAYMENT && listing.getPostingFeeBasisCents() != null
                && FeeCalculator.toCents(request.price()) > listing.getPostingFeeBasisCents()) {
            throw new ConflictException("The price can't be raised above what your posting fee covered ("
                    + com.campus.marketplace.service.PostingFeePolicy.format(listing.getPostingFeeBasisCents())
                    + "). Delete this listing and post it again at the new price.");
        }
        apply(request, listing);
        cacheEvictor.evictAssistantAnswers(id);
        // A listing that was waiting for its fee goes live as soon as it no longer needs one (the owner lowered
        // the price to the free tier, or posting fees were switched off).
        if (listing.getStatus() == ListingStatus.PENDING_PAYMENT && !postingFeePolicy.applies(listing.getPrice())) {
            listing.setStatus(ListingStatus.ACTIVE);
            listing.setPostingFeeBasisCents(postingFeePolicy.coveredCentsWithoutFee());
        }
        return ListingResponse.from(listing, currentUser.requireId());
    }

    /**
     * Owner only. Idempotent: marking an already-sold listing returns it unchanged. A reserved item can't be
     * marked sold by hand: the buyer's deposit would be stranded, so the seller must cancel the reservation
     * (which refunds it) first.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.FEE_QUOTE, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    @Transactional
    public ListingResponse markSold(Long id) {
        Listing listing = listingRepository.findForUpdateById(id).orElseThrow(() -> notFound(id));
        requireOwner(listing, "You can only mark your own listings as sold");
        if (listing.getStatus() == ListingStatus.PENDING_PAYMENT) {
            throw new ConflictException("This listing isn't published yet. Pay its posting fee first, or delete it.");
        }
        if (listing.getStatus() != ListingStatus.SOLD) {
            if (listing.isReserved()) {
                throw new ConflictException("This item is reserved. Cancel the reservation first; the buyer's deposit is refunded.");
            }
            listing.setStatus(ListingStatus.SOLD);
            listing.setSoldAt(Instant.now());
            cacheEvictor.evictAssistantAnswers(id);
        }
        return ListingResponse.from(listing, currentUser.requireId());
    }

    /**
     * Owner or ADMIN. A reserved item holds a buyer's deposit, so the owner must cancel the reservation
     * first. An admin removing it (moderation) refunds the deposit as part of the delete.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.FEE_QUOTE, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    @Transactional
    public void delete(Long id) {
        Listing listing = listingRepository.findForUpdateById(id)
                .orElseThrow(() -> notFound(id));
        boolean owner = isOwner(listing);
        if (!owner && !currentUser.isAdmin()) {
            throw new ForbiddenException("You can only delete your own listings");
        }
        if (listing.isReserved()) {
            if (owner) {
                throw new ConflictException("This item is reserved. Cancel the reservation first; the buyer's deposit is refunded.");
            }
            var refunded = paymentService.refundDeposit(listing);
            if (refunded != null) {
                refunded.setListing(null); // the payment outlives the listing; the database nulls the link too
            }
        }
        imageService.deleteFilesAfterCommit(listing.getImages().stream().map(i -> i.getPublicId())
                .filter(java.util.Objects::nonNull).toList());
        listingRepository.delete(listing);
        cacheEvictor.evictAssistantAnswers(id);
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
        // A listing with photos keeps their cover; the URL field only matters for one that has none.
        if (listing.getImages().isEmpty()) {
            listing.setImageUrl(request.imageUrl());
        }
        listing.setMeetupSpot(request.meetupSpot());
        listing.setItemCondition(request.itemCondition());
    }

    private static void validatePaging(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static Sort.Direction parseDirection(String direction) {
        return switch (direction.toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new BadRequestException("direction must be asc or desc");
        };
    }

    private static Pageable pageable(int page, int size, String sort, String direction) {
        validatePaging(page, size);
        String property = SORT_FIELDS.get(sort);
        if (property == null) {
            throw new BadRequestException("sort must be one of: " + String.join(", ", SORT_FIELDS.keySet().stream().sorted().toList()));
        }
        Sort.Direction dir = parseDirection(direction);
        // Tie-break on id so pages are stable when many rows share the same price/date.
        return PageRequest.of(page, size, Sort.by(dir, property).and(Sort.by(dir, "id")));
    }

    /** How long a listing may wait for its posting fee before it is deleted. */
    static final java.time.Duration PENDING_PAYMENT_TTL = java.time.Duration.ofHours(24);

    /** Deletes listings still waiting for their posting fee after 24 hours. Returns how many. */
    @Transactional
    public int deleteStalePending(Instant now) {
        int deleted = listingRepository.deleteStalePending(now.minus(PENDING_PAYMENT_TTL));
        if (deleted > 0) {
            log.info("Deleted {} listing(s) that never paid their posting fee", deleted);
        }
        return deleted;
    }

    private static ListingResponse rethrow(ResourceNotFoundException e) {
        throw e;
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Listing " + id + " not found");
    }
}
