package com.campus.marketplace.controller;

import com.campus.marketplace.dto.AdminKpiResponse;
import com.campus.marketplace.dto.AdminOrderResponse;
import com.campus.marketplace.service.AdminKpiService;
import com.campus.marketplace.dto.AdminReportResponse;
import com.campus.marketplace.dto.ResolveReportRequest;
import com.campus.marketplace.entity.ReportStatus;
import com.campus.marketplace.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.campus.marketplace.dto.AdminUserResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.service.AdminService;
import com.campus.marketplace.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every route here requires ROLE_ADMIN (SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final OrderService orderService;
    private final ReportService reportService;
    private final AdminKpiService kpiService;

    /** Headline numbers for the dashboard tiles. (Not /stats: a security test registers that path.) */
    @GetMapping("/kpis")
    public AdminKpiResponse kpis() {
        return kpiService.kpis();
    }

    @GetMapping("/users")
    public PageResponse<AdminUserResponse> listUsers(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return adminService.listUsers(page, size);
    }

    @PatchMapping("/users/{id}/deactivate")
    public AdminUserResponse deactivateUser(@PathVariable Long id) {
        return adminService.deactivateUser(id);
    }

    @PatchMapping("/users/{id}/reactivate")
    public AdminUserResponse reactivateUser(@PathVariable Long id) {
        return adminService.reactivateUser(id);
    }

    @GetMapping("/orders")
    public PageResponse<AdminOrderResponse> orders(@RequestParam(defaultValue = "false") boolean reported,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return orderService.all(reported, page, size);
    }

    /** Closes a buyer's problem report: COMPLETE (the item arrived) or CANCEL (the sale is called off). */
    @PatchMapping("/orders/{id}/resolve")
    public AdminOrderResponse resolve(@PathVariable Long id, @RequestParam OrderService.Resolution outcome) {
        return orderService.resolveProblem(id, outcome);
    }

    /** Records that the seller has been paid out. Only for a COMPLETED order. Idempotent. */
    @PatchMapping("/orders/{id}/payout")
    public AdminOrderResponse payout(@PathVariable Long id) {
        return orderService.markPaidOut(id);
    }

    /** The moderation queue. {@code status} is OPEN (default, oldest first) or RESOLVED. */
    @GetMapping("/reports")
    public PageResponse<AdminReportResponse> reports(@RequestParam(defaultValue = "OPEN") ReportStatus status,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return reportService.list(status, page, size);
    }

    /** Closes a report: RESOLVE (no action), DELETE_LISTING or DEACTIVATE_USER. */
    @PostMapping("/reports/{id}/resolve")
    public AdminReportResponse resolveReport(@PathVariable Long id, @Valid @RequestBody ResolveReportRequest request) {
        return reportService.resolve(id, request.action());
    }

    @DeleteMapping("/listings/{id}")
    public ResponseEntity<Void> deleteListing(@PathVariable Long id) {
        adminService.deleteListing(id);
        return ResponseEntity.noContent().build();
    }
}
