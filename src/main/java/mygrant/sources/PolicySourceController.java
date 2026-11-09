package mygrant.sources;

import java.security.Principal;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.common.Pages;

import mygrant.sources.dto.PolicySourceRequest;
import mygrant.sources.dto.PolicySourceResponse;

@RestController
@RequestMapping("/api/v1/admin/sources")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
public class PolicySourceController {

    private final PolicySourceService sourceService;

    public PolicySourceController(PolicySourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    @Operation(summary = "List monitored policy sources")
    public Page<PolicySourceResponse> findAll(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return Pages.of(sourceService.findAll(), pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a monitored policy source")
    public PolicySourceResponse findById(@PathVariable Long id) {
        return sourceService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a monitored policy source")
    public PolicySourceResponse create(@Valid @RequestBody PolicySourceRequest request,
            Principal principal) {
        return sourceService.create(request, principal);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a monitored policy source")
    public PolicySourceResponse update(@PathVariable Long id,
            @Valid @RequestBody PolicySourceRequest request, Principal principal) {
        return sourceService.update(id, request, principal);
    }

    @PatchMapping("/{id}/enabled")
    @Operation(summary = "Enable or disable a monitored policy source")
    public PolicySourceResponse setEnabled(@PathVariable Long id,
            @RequestBody EnabledRequest request, Principal principal) {
        return sourceService.setEnabled(id, request.enabled(), principal);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Disable a monitored policy source")
    public PolicySourceResponse disable(@PathVariable Long id, Principal principal) {
        return sourceService.setEnabled(id, false, principal);
    }

    public record EnabledRequest(boolean enabled) {
    }
}
