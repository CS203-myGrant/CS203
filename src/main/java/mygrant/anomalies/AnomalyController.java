package mygrant.anomalies;
import java.security.Principal; import java.time.LocalDate;
import org.springframework.data.domain.*; import org.springframework.security.access.prepost.PreAuthorize; import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid; import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
@RestController @RequestMapping("/api/v1/anomalies") @PreAuthorize("hasRole('ADMIN')")
@Tag(name="Admin")
public class AnomalyController {
 private final AnomalyRepository repo; private final AnomalyService service; public AnomalyController(AnomalyRepository r,AnomalyService s){repo=r;service=s;}
 @GetMapping @Operation(summary="List data anomalies") public Page<Anomaly> list(@RequestParam(required=false) AnomalyType type,@RequestParam(required=false) AnomalyStatus status,Pageable p){if(type!=null&&status!=null)return repo.findByTypeAndStatusOrderByCreatedAtDesc(type,status,p);if(type!=null)return repo.findByTypeOrderByCreatedAtDesc(type,p);if(status!=null)return repo.findByStatusOrderByCreatedAtDesc(status,p);return repo.findAllByOrderByCreatedAtDesc(p);}
 @PatchMapping("/{id}") @Operation(summary="Resolve or ignore a data anomaly") public Anomaly resolve(@PathVariable long id,@Valid @RequestBody Resolution r,Principal principal){return service.resolve(id,r.status,r.note,r.correctEffectiveDate,principal.getName());}
 public record Resolution(@NotNull AnomalyStatus status,@NotBlank @Size(max=2000) String note,LocalDate correctEffectiveDate){}
}
