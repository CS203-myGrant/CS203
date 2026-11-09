package mygrant.anomalies;

import java.time.LocalDate;
import java.util.Set;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.rules.*;

@Service
public class AnomalyService {
 private final AnomalyRepository anomalies; private final PolicyRuleStore rules; private final PolicyDocumentRepository policies; private final ApplicationEventPublisher events;
 public AnomalyService(AnomalyRepository a,PolicyRuleStore r,PolicyDocumentRepository p,ApplicationEventPublisher e){anomalies=a;rules=r;policies=p;events=e;}
 @Transactional public Anomaly resolve(long id,AnomalyStatus status,String note,LocalDate date,String email){
  Anomaly a=anomalies.findById(id).orElseThrow(()->new AnomalyNotFoundException(id));
  if(status==AnomalyStatus.OPEN)throw new IllegalArgumentException("An anomaly can only be resolved or ignored"); if(a.getStatus()!=AnomalyStatus.OPEN)throw new IllegalArgumentException("Anomaly is already resolved");
  if(a.getType()==AnomalyType.EFFECTIVE_DATE_CONFLICT && status==AnomalyStatus.RESOLVED){ if(date==null)throw new IllegalArgumentException("correctEffectiveDate is required for a date conflict"); applyDate(a,date,email,note); }
  a.resolve(status,note,date,email); return anomalies.save(a);
 }
 @Transactional public void recordSourceFailure(long sourceId,String documentId,String error,RuntimeException failure){
  AnomalyType type=classificationFailure(failure)?AnomalyType.CLASSIFICATION_FAILED:AnomalyType.MALFORMED_SOURCE_DATA;
  String prefix="Document "+documentId+": ";
  if(anomalies.findFirstByTypeAndSourceIdAndStatusAndDetailsStartingWithOrderByCreatedAtDesc(type,sourceId,AnomalyStatus.OPEN,prefix).isEmpty())
   anomalies.save(new Anomaly(type,null,null,sourceId,prefix+error));
 }
 private static boolean classificationFailure(RuntimeException e){String s=(e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage())).toLowerCase();return s.contains("classif")||s.contains("extract")||s.contains("visa category");}
 private void applyDate(Anomaly a,LocalDate date,String email,String note){
  if(a.getRuleId()==null||a.getPolicyId()==null)throw new IllegalArgumentException("Date conflict has no linked rule and policy");
  PolicyRuleStore.Rule before=rules.findRule(a.getRuleId()).orElseThrow(()->new IllegalArgumentException("Rule not found")); PolicyDocument source=policies.findById(a.getPolicyId()).orElseThrow(()->new IllegalArgumentException("Policy not found"));
  RuleState after=new RuleState(before.state().primaryStage(),date,before.state().disputed()); long history=rules.applyAdminEffectiveDate(before,after,RuleSource.of(source),email,note);
  events.publishEvent(new PolicyChangedEvent(before.id(),history,before.title(),before.state(),after,Set.of(RuleChange.EFFECTIVE_DATE),RuleSource.of(source),List.of(source.getVisaType().split(",")),source.getTitle(),source.getContent()));
 }
}
