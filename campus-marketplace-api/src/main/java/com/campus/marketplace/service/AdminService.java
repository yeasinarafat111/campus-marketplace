package com.campus.marketplace.service;

import com.campus.marketplace.config.CacheNames;
import com.campus.marketplace.dto.AdminUserResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Admin-only operations. Access is enforced by SecurityConfig (/api/admin/** requires ROLE_ADMIN). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final CurrentUserProvider currentUser;
    private final PaymentService paymentService;
    private final ListingImageService imageService;

    /** All users, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> listUsers(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > ListingService.MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + ListingService.MAX_PAGE_SIZE);
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.of(userRepository.findAll(pageable), AdminUserResponse::from);
    }

    /**
     * Blocks the account: existing access tokens stop working on the next request, refresh and login are
     * refused, email verification can't re-enable it, and the user's listings are hidden from public
     * browsing. Idempotent. Admins can't deactivate themselves.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_PROFILE, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.FEE_QUOTE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    @Transactional
    public AdminUserResponse deactivateUser(Long id) {
        if (Objects.equals(id, currentUser.requireId())) {
            throw new BadRequestException("You cannot deactivate your own account");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " not found"));
        if (user.isActive()) {
            user.setActive(false);
            log.info("Admin {} deactivated user {} ({})", currentUser.requireId(), user.getId(), user.getEmail());
        }
        return AdminUserResponse.from(user);
    }

    /**
     * Undoes {@link #deactivateUser}. Idempotent. Email verification status is left as it was, so an
     * account that was never verified still has to verify before logging in.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_PROFILE, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.FEE_QUOTE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    @Transactional
    public AdminUserResponse reactivateUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " not found"));
        if (!user.isActive()) {
            user.setActive(true);
            log.info("Admin {} reactivated user {} ({})", currentUser.requireId(), user.getId(), user.getEmail());
        }
        return AdminUserResponse.from(user);
    }

    /** The payment stays on record when its listing goes (the database nulls the link); mirror that in memory. */
    private static void detachFromListing(com.campus.marketplace.entity.Payment payment) {
        if (payment != null) {
            payment.setListing(null);
        }
    }

    /** Hard delete, regardless of owner. A reserved item's deposit is refunded to the buyer first. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.FEE_QUOTE, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    @Transactional
    public void deleteListing(Long id) {
        var listing = listingRepository.findForUpdateById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Listing " + id + " not found"));
        if (listing.isReserved()) {
            detachFromListing(paymentService.refundDeposit(listing));
        }
        imageService.deleteFilesAfterCommit(listing.getImages().stream().map(i -> i.getPublicId())
                .filter(Objects::nonNull).toList());
        listingRepository.delete(listing);
        log.info("Admin {} deleted listing {}", currentUser.requireId(), id);
    }
}
