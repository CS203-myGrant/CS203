package mygrant.anomalies;
import java.time.Instant;
import jakarta.persistence.*;
@Entity @Table(name="anomalies")
public class Anomaly {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private AnomalyType type;
 @Column(name="policy_id") private Long policyId; @Column(name="rule_id") private Long ruleId; @Column(name="source_id") private Long sourceId;
 @Column(nullable=false,columnDefinition="TEXT") private String details;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private AnomalyStatus status=AnomalyStatus.OPEN;
 @Column(name="correct_effective_date") private java.time.LocalDate correctEffectiveDate;
 @Column(name="resolution_note",columnDefinition="TEXT") private String resolutionNote;
 @Column(name="resolved_by") private String resolvedBy; @Column(name="created_at") private Instant createdAt; @Column(name="updated_at") private Instant updatedAt; @Column(name="resolved_at") private Instant resolvedAt;
 protected Anomaly(){} public Anomaly(AnomalyType t,Long p,Long r,Long s,String d){type=t;policyId=p;ruleId=r;sourceId=s;details=d;createdAt=updatedAt=Instant.now();}
 public void resolve(AnomalyStatus s,String note,java.time.LocalDate date,String by){status=s;resolutionNote=note;correctEffectiveDate=date;resolvedBy=by;resolvedAt=updatedAt=Instant.now();}
 public Long getId(){return id;} public AnomalyType getType(){return type;} public Long getPolicyId(){return policyId;} public Long getRuleId(){return ruleId;} public Long getSourceId(){return sourceId;} public String getDetails(){return details;} public AnomalyStatus getStatus(){return status;} public java.time.LocalDate getCorrectEffectiveDate(){return correctEffectiveDate;} public String getResolutionNote(){return resolutionNote;} public String getResolvedBy(){return resolvedBy;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;} public Instant getResolvedAt(){return resolvedAt;}
}
