package mygrant.anomalies;
import org.springframework.context.event.EventListener; import org.springframework.stereotype.Component;
@Component public class EffectiveDateConflictListener { private final AnomalyRepository repo; public EffectiveDateConflictListener(AnomalyRepository r){repo=r;} @EventListener public void record(EffectiveDateConflictEvent e){repo.save(new Anomaly(AnomalyType.EFFECTIVE_DATE_CONFLICT,e.policyId(),e.ruleId(),null,e.details()+" Stored date: "+e.storedDate()+"; supplied date: "+e.suppliedDate()));} }
