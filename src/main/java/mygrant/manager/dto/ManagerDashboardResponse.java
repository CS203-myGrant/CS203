package mygrant.manager.dto;

import java.util.List;
import java.util.Map;

import mygrant.impact.ImpactReviewService.PendingImpact;

/** Aggregate policy impact for the applicants linked to one manager. */
public record ManagerDashboardResponse(
        long linkedUserCount,
        Map<String, Long> usersByVisaType,
        Map<String, Long> openImpactsBySeverity,
        List<PendingImpact> pendingReports) {}
