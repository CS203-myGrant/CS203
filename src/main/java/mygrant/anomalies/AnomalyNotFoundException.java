package mygrant.anomalies;
public class AnomalyNotFoundException extends RuntimeException { public AnomalyNotFoundException(long id){super("Anomaly not found: " + id);} }
