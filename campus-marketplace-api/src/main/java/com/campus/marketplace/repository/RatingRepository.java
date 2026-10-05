package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Rating;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RatingRepository extends JpaRepository<Rating, Long> {

    interface Summary {
        Double getAverage();

        long getCount();
    }

    boolean existsByOrderId(Long orderId);

    @Query("select r from Rating r where r.order.id in :orderIds")
    List<Rating> findByOrderIds(@Param("orderIds") Collection<Long> orderIds);

    @Query("select avg(r.stars) as average, count(r) as count from Rating r where r.seller.id = :sellerId")
    Summary summaryForSeller(@Param("sellerId") Long sellerId);

    @EntityGraph(attributePaths = "buyer")
    Page<Rating> findBySellerId(Long sellerId, Pageable pageable);
}
