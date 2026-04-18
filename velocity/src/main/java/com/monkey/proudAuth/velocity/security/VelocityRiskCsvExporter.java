package com.monkey.proudAuth.velocity.security;

import com.monkey.proudAuth.common.storage.IpHistoryStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class VelocityRiskCsvExporter {

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final VelocitySecurityInspectorService inspectorService;
    private final Path reportDirectory;

    public VelocityRiskCsvExporter(VelocitySecurityInspectorService inspectorService, Path reportDirectory) {
        this.inspectorService = inspectorService;
        this.reportDirectory = reportDirectory;
    }

    public Path export(Duration window, int limit) throws IOException {
        Files.createDirectories(reportDirectory);
        VelocitySecurityInspectorService.TopRiskReport report = inspectorService.inspectTopRisks(window, limit).join();

        String timestamp = FILE_TS.format(Instant.now());
        Path output = reportDirectory.resolve("risk-report-" + timestamp + ".csv");
        StringBuilder csv = new StringBuilder();
        csv.append("section,key,metric,value\n");

        for (IpHistoryStorage.IpSummary row : report.topIpsByUserSpread()) {
            csv.append("top_ip,").append(escape(row.ipAddress())).append(",distinct_usernames,").append(row.distinctUsernames()).append('\n');
            csv.append("top_ip,").append(escape(row.ipAddress())).append(",total_hits,").append(row.totalHits()).append('\n');
        }
        for (IpHistoryStorage.UserSummary row : report.topUsersByIpSpread()) {
            csv.append("top_user,").append(escape(row.username())).append(",distinct_ips,").append(row.distinctIps()).append('\n');
            csv.append("top_user,").append(escape(row.username())).append(",total_hits,").append(row.totalHits()).append('\n');
        }

        Files.writeString(output, csv.toString(), StandardCharsets.UTF_8);
        return output;
    }

    private static String escape(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
