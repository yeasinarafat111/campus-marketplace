package com.campus.marketplace.service;

import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.SavedListing;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.SavedListingRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Bookmarks. Viewer-specific, so nothing here touches the public listing caches. */
@Service
@RequiredArgsConstructor
public class SavedListingService {

    private static final int MAX_PAGE_SIZE = 50;

    private final SavedListingRepository savedRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUser;

    /** Idempotent: saving twice leaves one row. */
    public void save(Long listingId) {
        Long userId = currentUser.requireId();
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing " + listingId + " not found"));
        // A listing that isn't public yet (waiting for its fee) is invisible to everyone but its owner.
        if (listing.getStatus() == ListingStatus.PENDING_PAYMENT) {
            throw new ResourceNotFoundException("Listing " + listingId + " not found");
        }
        if (savedRepository.existsByUserIdAndListingId(userId, listingId)) {
            return;
        }
        try {
            savedRepository.saveAndFlush(SavedListing.builder()
                    .user(userRepository.getReferenceById(userId)).listing(listing).build());
        } catch (DataIntegrityViolationException e) {
            // Two taps at once: the other request won, which is the outcome we wanted.
        }
    }

    /** Idempotent: removing something not saved is fine. */
    @Transactional
    public void unsave(Long listingId) {
        savedRepository.deleteByUserAndListing(currentUser.requireId(), listingId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ListingResponse> mine(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Long userId = currentUser.requireId();
        var result = savedRepository.findVisibleByUser(userId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
        return PageResponse.of(result, s -> ListingResponse.from(s.getListing(), userId));
    }

    /** Just the ids, so cards on any page can show a filled heart without every listing carrying a per-viewer flag. */
    @Transactional(readOnly = true)
    public List<Long> mineIds() {
        return savedRepository.findListingIdsByUser(Objects.requireNonNull(currentUser.requireId()));
    }
}
