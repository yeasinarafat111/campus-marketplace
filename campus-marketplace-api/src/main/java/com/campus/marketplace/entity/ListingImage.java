package com.campus.marketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One photo of a listing, in display order ({@code position} 0 is the cover, also kept in {@code Listing.imageUrl}).
 * {@code publicId} is the Cloudinary id used to delete the file; null for a photo given only as a URL.
 */
@Entity
@Table(name = "listing_images", indexes = @Index(name = "idx_listing_images_listing", columnList = "listing_id, position"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "listing")
public class ListingImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Listing listing;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(nullable = false)
    private int position;

    @Column(length = 255)
    private String publicId;
}
