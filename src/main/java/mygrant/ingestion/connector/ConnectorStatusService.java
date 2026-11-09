package mygrant.ingestion.connector;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

/** One health view for every installed data connector. */
@Service
public class ConnectorStatusService {
    private final List<DataConnector<?, ?, ?, ?>> connectors;

    public ConnectorStatusService(List<DataConnector<?, ?, ?, ?>> connectors) {
        this.connectors = connectors;
    }

    public List<ConnectorHealth> status() {
        return connectors.stream().map(DataConnector::health)
                .sorted(Comparator.comparing(health -> health.source().id()))
                .toList();
    }
}
