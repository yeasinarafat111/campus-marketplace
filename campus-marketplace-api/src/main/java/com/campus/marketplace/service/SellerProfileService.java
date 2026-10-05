package com.campus.marketplace.service;

import com.campus.marketplace.dto.SellerProfileResponse;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.RatingRepository;
import com.campus.marketplace.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The public page about a seller. Deactivated and unverified accounts don't have one. */
@Service
@RequiredArgsConstructor
public class SellerProfileService {

    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final RatingRepository ratingRepository;

    @Transactional(readOnly = true)
    public SellerProfileResponse get(Long userId) {
        User user = userRepository.findById(userId)
                .filter(u -> u.isActive() && u.isEnabled())
                .orElseThrow(() -> new ResourceNotFoundException("User " + userId + " not found"));
        var summary = ratingRepository.summaryForSeller(userId);
        return new SellerProfileResponse(
                user.getId(),
                user.getFullName(),
                user.getCreatedAt(),
                listingRepository.countByOwnerIdAndStatus(userId, ListingStatus.ACTIVE),
                listingRepository.countByOwnerIdAndStatus(userId, ListingStatus.SOLD),
                summary.getCount() == 0 ? null : Math.round(summary.getAverage() * 10) / 10.0,
                summary.getCount());
    }
}
