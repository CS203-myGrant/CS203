package mygrant.anomalies;
import java.time.LocalDate;
public record EffectiveDateConflictEvent(long policyId,long ruleId,LocalDate storedDate,LocalDate suppliedDate,String details) {}
