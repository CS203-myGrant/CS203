package mygrant.impact;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mygrant.impact")
public record ImpactAssessmentProperties(@DefaultValue("60") @Min(0) @Max(100) int reviewConfidenceThreshold) {}
