package com.campus.marketplace.controller;

import com.campus.marketplace.dto.FeeQuote;
import com.campus.marketplace.dto.ListingRequest;
import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.dto.OrderResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.dto.PostingFeeQuote;
import com.campus.marketplace.dto.ReorderImagesRequest;
import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.ItemCondition;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.service.ListingImageService;
import com.campus.marketplace.service.ListingService;
import com.campus.marketplace.service.SavedListingService;
import com.campus.marketplace.service.PaymentService;
import com.campus.marketplace.service.PublicListingReader;
import com.campus.marketplace.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Access rules: GET /api/listings and GET /api/listings/{id} are public (see SecurityConfig);
 * everything else requires a token. Ownership and ADMIN checks happen in {@link ListingService}.
 */
@RestController
@RequestMapping("/api/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;
    private final PaymentService paymentService;
    private final ReservationService reservationService;
    private final PublicListingReader publicListingReader;
    private final ListingImageService imageService;
    private final SavedListingService savedListingService;

    @GetMapping
    public PageResponse<ListingResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) ItemCondition condition,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            // Omit to get featured listings first, then newest.
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return listingService.search(keyword, category, minPrice, maxPrice, ownerId, featured, condition, page, size, sort,
                direction);
    }

    /**
     * The posting fee for a price, for the create page's live quote. Public arithmetic: a price at or below the
     * threshold is free.
     */
    @GetMapping("/posting-fee")
    public PostingFeeQuote postingFee(@RequestParam BigDecimal price) {
        return paymentService.postingFeeQuote(price);
    }

    /** The front page's Featured row. Public; cached for a minute. */
    @GetMapping("/featured")
    public List<ListingResponse> featured() {
        return publicListingReader.featured();
    }

    /** Active listings per category, for the chips. Public; cached for five minutes. */
    @GetMapping("/category-counts")
    public Map<Category, Long> categoryCounts() {
        return publicListingReader.categoryCounts();
    }

    // Declared before /{id} for readability; Spring prefers the literal path either way.
    @GetMapping("/mine")
    public PageResponse<ListingResponse> mine(
            @RequestParam(required = false) ListingStatus status,
            @RequestParam(required = false) Boolean reserved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return listingService.mine(status, reserved, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    public ListingResponse get(@PathVariable Long id) {
        return listingService.get(id);
    }

    /** Bookmarks the listing for the caller. Idempotent. */
    @PostMapping("/{id}/save")
    public ResponseEntity<Void> save(@PathVariable Long id) {
        savedListingService.save(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/save")
    public ResponseEntity<Void> unsave(@PathVariable Long id) {
        savedListingService.unsave(id);
        return ResponseEntity.noContent().build();
    }

    /** Adds a photo (multipart field {@code image}), up to five. Owner only. */
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ListingResponse addImage(@PathVariable Long id, @RequestPart("image") MultipartFile image) {
        return imageService.add(id, image);
    }

    @DeleteMapping("/{id}/images/{imageId}")
    public ListingResponse removeImage(@PathVariable Long id, @PathVariable Long imageId) {
        return imageService.remove(id, imageId);
    }

    /** The first photo in the list becomes the cover. */
    @PutMapping("/{id}/images/order")
    public ListingResponse reorderImages(@PathVariable Long id, @Valid @RequestBody ReorderImagesRequest request) {
        return imageService.reorder(id, request);
    }

    /** Seller cancels a reservation; the buyer's deposit is refunded in full. */
    @PostMapping("/{id}/reservation/cancel")
    public ListingResponse cancelReservation(@PathVariable Long id) {
        return reservationService.cancelAsSeller(id);
    }

    /** Reserver: "I'll pay cash at the meetup". Records the choice; nothing is charged. */
    @PostMapping("/{id}/cash")
    public ListingResponse chooseCash(@PathVariable Long id) {
        return reservationService.chooseCash(id);
    }

    /** Seller: "Confirm handover and cash received". Sells the item and completes the cash order. */
    @PostMapping("/{id}/cash/confirm")
    public OrderResponse confirmCashHandover(@PathVariable Long id) {
        return reservationService.confirmCashHandover(id);
    }

    /** Buyer gives the item up; the deposit is kept. */
    @PostMapping("/{id}/reservation/release")
    public ListingResponse releaseReservation(@PathVariable Long id) {
        return reservationService.releaseAsBuyer(id);
    }

    /** What it would cost the caller to feature or reserve this listing. Needs a login (see SecurityConfig). */
    @GetMapping("/{id}/fees")
    public FeeQuote fees(@PathVariable Long id) {
        return paymentService.quote(id);
    }

    /** JSON body; the image, if any, is given as {@code imageUrl}. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ListingResponse> create(@Valid @RequestBody ListingRequest request) {
        return created(listingService.create(request));
    }

    /**
     * multipart/form-data: the listing fields as form fields (title, description, price, category) plus an
     * optional {@code image} file, which is uploaded to Cloudinary and overrides any {@code imageUrl} field.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ListingResponse> createWithImage(
            @Valid @ModelAttribute ListingRequest request,
            @RequestPart(name = "image", required = false) MultipartFile image,
            @RequestPart(name = "images", required = false) List<MultipartFile> images) {
        List<MultipartFile> files = new java.util.ArrayList<>();
        if (image != null) {
            files.add(image);
        }
        if (images != null) {
            files.addAll(images);
        }
        return created(listingService.create(request, files));
    }

    private static ResponseEntity<ListingResponse> created(ListingResponse listing) {
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(listing.id()).toUri();
        return ResponseEntity.created(location).body(listing);
    }

    @PutMapping("/{id}")
    public ListingResponse update(@PathVariable Long id, @Valid @RequestBody ListingRequest request) {
        return listingService.update(id, request);
    }

    @PatchMapping("/{id}/sold")
    public ListingResponse markSold(@PathVariable Long id) {
        return listingService.markSold(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        listingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
