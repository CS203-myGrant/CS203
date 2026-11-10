package mygrant.ingestion.visabulletin;

import java.security.Principal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Each applicant can see and change only their own optional tracking subscription. */
@RestController
@RequestMapping("/api/v1/visa-bulletin/tracking")
@PreAuthorize("hasRole('APPLICANT')")
@Tag(name = "Visa Bulletin tracking")
public class VisaBulletinTrackingController {
    private final VisaBulletinTrackingService tracking;

    public VisaBulletinTrackingController(VisaBulletinTrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping
    @Operation(summary = "Get your Visa Bulletin tracking, current chart values, and change alerts")
    public VisaBulletinTrackingService.TrackingView get(Principal principal) {
        return tracking.view(principal.getName());
    }

    @PutMapping
    @Operation(summary = "Opt in or out of Visa Bulletin cutoff-change alerts")
    public VisaBulletinTrackingService.TrackingView set(Principal principal,
            @RequestBody TrackingRequest request) {
        if (request == null || request.enabled() == null) {
            throw new IllegalArgumentException("enabled must be true or false");
        }
        return tracking.setTracking(principal.getName(), request.enabled());
    }

    public record TrackingRequest(Boolean enabled) {}
}
