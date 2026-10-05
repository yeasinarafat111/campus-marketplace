package com.campus.marketplace.service;

import com.campus.marketplace.dto.AdminKpiResponse;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.ReportStatus;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.ReportRepository;
import com.campus.marketplace.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Headline counts for the admin dashboard. Access is enforced by SecurityConfig (/api/admin/**). */
@Service
@RequiredArgsConstructor
public class AdminKpiService {

    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final OrderRepository orderRepository;
    private final ReportRepository reportRepository;
    private final RevenueService revenueService;

    @Transactional(readOnly = true)
    public AdminKpiResponse kpis() {
        var week = revenueService.thisWeek();
        return new AdminKpiResponse(
                userRepository.countByEnabledTrueAndActiveTrue(),
                listingRepository.countByStatus(ListingStatus.ACTIVE),
                orderRepository.count(),
                week.netCents(),
                week.currency(),
                week.from(),
                reportRepository.countByStatus(ReportStatus.OPEN),
                orderRepository.countByProblemReportedAtIsNotNullAndStatus(OrderStatus.PAID));
    }
}
