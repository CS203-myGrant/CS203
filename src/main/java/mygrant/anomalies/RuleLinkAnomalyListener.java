package mygrant.anomalies;
import org.springframework.context.event.EventListener; import org.springframework.stereotype.Component; import mygrant.rules.RuleLinkFlaggedEvent;
@Component public class RuleLinkAnomalyListener { private final AnomalyRepository repo; public RuleLinkAnomalyListener(AnomalyRepository r){repo=r;} @EventListener public void flagged(RuleLinkFlaggedEvent e){repo.save(new Anomaly(AnomalyType.UNCERTAIN_RULE_LINK,e.policyId(),e.ruleId(),null,e.reason()));} }
