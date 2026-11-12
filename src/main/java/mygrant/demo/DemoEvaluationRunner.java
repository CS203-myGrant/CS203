package mygrant.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import mygrant.impact.ImpactUrgency;
import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.policies.PolicyAssessmentInputs;
import mygrant.policies.PolicyDocumentRepository;

/** Runs only when the explicit demo evaluation flag is set. */
@Component
public class DemoEvaluationRunner {
    private final ProfileImpactAssessmentService assessments;
    private final PolicyDocumentRepository policies;
    private final Path reportDirectory;
    private final String openAiKey;
    private final String anthropicKey;

    public DemoEvaluationRunner(ProfileImpactAssessmentService assessments, PolicyDocumentRepository policies,
            @Value("${mygrant.demo.report-directory:demo-reports}") String reportDirectory,
            @Value("${openai.api-key:}") String openAiKey,
            @Value("${anthropic.api-key:}") String anthropicKey) {
        this.assessments = assessments;
        this.policies = policies;
        this.reportDirectory = Path.of(reportDirectory);
        this.openAiKey = openAiKey;
        this.anthropicKey = anthropicKey;
    }

    public void run() throws IOException {
        if (openAiKey.isBlank() || anthropicKey.isBlank() || "not-configured".equals(openAiKey)) {
            throw new IllegalStateException("Live evaluation needs OPENAI_API_KEY and ANTHROPIC_API_KEY");
        }
        Files.createDirectories(reportDirectory);
        Instant runAt = Instant.now();
        List<Row> rows = new ArrayList<>();
        for (var scenario : DemoScenarioCatalog.SCENARIOS) {
            var policy = policies.findByExternalId(scenario.documentNumber())
                    .orElseThrow(() -> new IllegalStateException("Missing demo policy " + scenario.documentNumber()));
            var result = assessments.assess(scenario.email(), policy,
                    PolicyAssessmentInputs.document(policy), PolicyAssessmentInputs.classification(policy));
            rows.add(new Row(runAt, scenario.name(), scenario.documentNumber(), scenario.expected(),
                    result.urgency(), result.requiresHumanReview(),
                    result.evaluation() == null ? "NONE" : result.evaluation().verdict().name(),
                    result.model()));
        }
        writeReport(rows);
    }

    void writeReport(List<Row> rows) throws IOException {
        Path history = reportDirectory.resolve("history.csv");
        if (!Files.exists(history)) {
            Files.writeString(history, "run_at,scenario,document,expected,actual,agreement,review_required,judge,model\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        StringBuilder csv = new StringBuilder();
        for (Row row : rows) {
            csv.append(row.runAt()).append(',').append(quoted(row.scenario())).append(',')
                    .append(row.document()).append(',').append(row.expected()).append(',')
                    .append(row.actual() == null ? "UNAVAILABLE" : row.actual()).append(',')
                    .append(eligible(row) && row.expected() == row.actual()).append(',')
                    .append(row.review()).append(',').append(row.judge()).append(',')
                    .append(quoted(row.model())).append('\n');
        }
        Files.writeString(history, csv.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        long available = rows.stream().filter(this::eligible).count();
        long agreed = rows.stream().filter(this::eligible)
                .filter(row -> row.expected() == row.actual()).count();
        long reviews = rows.stream().filter(Row::review).count();
        StringBuilder markdown = new StringBuilder("# Persona live-model evaluation\n\n")
                .append("Run: ").append(rows.getFirst().runAt()).append("\n\n")
                .append("Agreement: ").append(agreed).append('/').append(available)
                .append(" available results; unavailable: ").append(rows.size() - available)
                .append('/').append(rows.size()).append("; review required: ")
                .append(reviews).append('/').append(rows.size()).append(".\n\n")
                .append("These are team-judged expectations, not a legal accuracy score. A rejected or unavailable "
                        + "assessment counts as unavailable. Student C is evaluated per policy; combined reasoning "
                        + "requires CS203-81. Employer assessment is the linked employee's view until CS203-79.\n\n")
                .append("| Scenario | Document | Expected | Actual | Agrees | Review? | Judge | Model |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            markdown.append('|').append(row.scenario()).append('|').append(row.document()).append('|')
                    .append(row.expected()).append('|').append(row.actual() == null ? "UNAVAILABLE" : row.actual()).append('|')
                    .append(eligible(row) && row.expected() == row.actual() ? "yes" : "no")
                    .append('|').append(row.review()).append('|').append(row.judge()).append('|')
                    .append(row.model()).append("|\n");
        }
        Files.writeString(reportDirectory.resolve("latest.md"), markdown.toString(), StandardCharsets.UTF_8);
        writeHistoryGraphic(history);
    }

    private void writeHistoryGraphic(Path history) throws IOException {
        // The last two metrics are calculated from saved CSV rows, so this remains useful after repeated runs.
        List<String> lines = Files.readAllLines(history, StandardCharsets.UTF_8);
        java.util.Map<String, int[]> runs = new java.util.LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            if (cells.length < 9) continue;
            int[] totals = runs.computeIfAbsent(cells[0], ignored -> new int[4]);
            totals[0]++;
            if (!"UNAVAILABLE".equals(cells[4]) && "YES".equals(cells[7])) {
                totals[1]++;
                if (Boolean.parseBoolean(cells[5])) totals[2]++;
            }
            if (Boolean.parseBoolean(cells[6])) totals[3]++;
        }
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"")
                .append(95 + runs.size() * 64).append("\" role=\"img\" aria-label=\"Historical agreement and review rates\">")
                .append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>")
                .append("<text x=\"20\" y=\"30\" font-size=\"20\">Live evaluation history</text>")
                .append("<text x=\"20\" y=\"52\" font-size=\"12\">Blue: agreement among available results; orange: human-review rate</text>");
        int y = 80;
        for (var entry : runs.entrySet()) {
            int[] totals = entry.getValue();
            double agreement = totals[1] == 0 ? 0 : 100.0 * totals[2] / totals[1];
            double review = totals[0] == 0 ? 0 : 100.0 * totals[3] / totals[0];
            svg.append("<text x=\"20\" y=\"").append(y + 10).append("\" font-size=\"12\">")
                    .append(entry.getKey()).append("</text>")
                    .append("<rect x=\"230\" y=\"").append(y).append("\" width=\"")
                    .append(Math.round(agreement * 3)).append("\" height=\"16\" fill=\"#1565c0\"/>")
                    .append("<rect x=\"230\" y=\"").append(y + 20).append("\" width=\"")
                    .append(Math.round(review * 3)).append("\" height=\"16\" fill=\"#ef6c00\"/>")
                    .append("<text x=\"550\" y=\"").append(y + 13).append("\" font-size=\"12\">")
                    .append(String.format(Locale.ROOT, "%.0f%% (%d/%d)", agreement, totals[2], totals[1]))
                    .append("</text><text x=\"550\" y=\"").append(y + 34).append("\" font-size=\"12\">")
                    .append(String.format(Locale.ROOT, "%.0f%% (%d/%d)", review, totals[3], totals[0]))
                    .append("</text>");
            y += 64;
        }
        Files.writeString(reportDirectory.resolve("history.svg"), svg.append("</svg>").toString(),
                StandardCharsets.UTF_8);
    }

    private String quoted(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private boolean eligible(Row row) {
        return row.actual() != null && "YES".equals(row.judge());
    }

    record Row(Instant runAt, String scenario, String document, ImpactUrgency expected,
            ImpactUrgency actual, boolean review, String judge, String model) {}
}
