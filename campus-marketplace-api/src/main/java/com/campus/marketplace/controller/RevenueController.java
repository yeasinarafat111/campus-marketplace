package com.campus.marketplace.controller;

import com.campus.marketplace.dto.LedgerEntryResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.dto.RevenueReport;
import com.campus.marketplace.entity.LedgerType;
import com.campus.marketplace.service.RevenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** Platform revenue. Every route requires ROLE_ADMIN (SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/revenue")
@RequiredArgsConstructor
public class RevenueController {

    private final RevenueService revenueService;

    /** Totals per source, net revenue and a series for a date range (default: the last 30 days), before Stripe's fees. */
    @GetMapping
    public RevenueReport report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String groupBy) {
        return revenueService.report(from, to, RevenueService.GroupBy.parse(groupBy));
    }

    /** The ledger lines, newest first. */
    @GetMapping("/entries")
    public PageResponse<LedgerEntryResponse> entries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) LedgerType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return revenueService.entries(from, to, type, page, size);
    }

    /** The same lines as a CSV download. */
    @GetMapping("/entries/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) LedgerType type) {
        byte[] body = revenueService.csv(from, to, type).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("revenue.csv").build().toString())
                .body(body);
    }
}
