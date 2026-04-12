package com.rag.infrastructure.extraction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.config.AppConfig;
import com.rag.infrastructure.extraction.model.TableContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class TableParserService {

    private static final Logger log = LoggerFactory.getLogger(TableParserService.class);

    private final AppConfig.Extraction extractionConfig;
    private final AppConfig.Extraction.Table tableConfig;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public TableParserService(AppConfig appConfig, ObjectMapper objectMapper) {
        this.extractionConfig = appConfig.getExtraction();
        this.tableConfig = extractionConfig.getTable();
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl("https://formrecognizer." + tableConfig.getParser().getRegion() + ".aliyuncs.com")
                .defaultHeader("Content-Type", "application/json; charset=utf-8")
                .build();
        log.info("TableParserService initialized: enabled={}, provider={}",
                tableConfig.isEnabled(), tableConfig.getParser().getProvider());
    }

    public TableContent parseTableToHtml(TableContent table) {
        if (!extractionConfig.isEnabled() || !tableConfig.isEnabled()) {
            log.debug("Table parsing is disabled, returning raw content");
            return table;
        }

        try {
            String tableBase64 = Base64.getEncoder().encodeToString(table.getData());
            String requestBody = buildRequestBody(tableBase64);

            String response = webClient.post()
                    .uri("/api/v1.0/recognize/table")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Ocp-Apim-Subscription-Key", tableConfig.getParser().getApiKey())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .block();

            parseTableResponse(response, table);
            log.debug("Parsed table {}: {} rows, {} columns",
                    table.getTableId(), table.getRows(), table.getColumns());

        } catch (Exception e) {
            log.error("Failed to parse table {}: {}", table.getTableId(), e.getMessage());
        }

        return table;
    }

    public List<TableContent> parseTables(List<TableContent> tables) {
        if (!extractionConfig.isEnabled() || !tableConfig.isEnabled()) {
            return tables;
        }

        AppConfig.Extraction.Processing processingConfig = extractionConfig.getProcessing();

        if (processingConfig.isParallel()) {
            return tables.parallelStream()
                    .map(this::parseTableToHtml)
                    .collect(Collectors.toList());
        } else {
            return tables.stream()
                    .map(this::parseTableToHtml)
                    .collect(Collectors.toList());
        }
    }

    private String buildRequestBody(String tableBase64) {
        String outputFormat = tableConfig.getParser().getOutputFormat();
        return String.format("""
            {
                "image": "%s",
                "outputFormat": "%s"
            }
            """, tableBase64, outputFormat);
    }

    private void parseTableResponse(String response, TableContent table) throws Exception {
        JsonNode root = objectMapper.readTree(response);

        if (root.has("success") && root.get("success").asBoolean()) {
            JsonNode data = root.get("data");

            if ("html".equalsIgnoreCase(tableConfig.getParser().getOutputFormat())) {
                if (data.has("html")) {
                    table.setHtmlContent(data.get("html").asText());
                }
            } else if ("json".equalsIgnoreCase(tableConfig.getParser().getOutputFormat())) {
                if (data.has("json")) {
                    table.setJsonContent(data.get("json").asText());
                }
            }

            if (data.has("rows")) {
                table.setRows(data.get("rows").asInt());
            }
            if (data.has("columns")) {
                table.setColumns(data.get("columns").asInt());
            }
            if (data.has("headers")) {
                ArrayNode headers = (ArrayNode) data.get("headers");
                String[] headerArray = new String[headers.size()];
                for (int i = 0; i < headers.size(); i++) {
                    headerArray[i] = headers.get(i).asText();
                }
                table.setHeaders(headerArray);
            }
            if (data.has("confidence")) {
                table.setConfidence((float) data.get("confidence").asDouble());
            }

            if (table.getHtmlContent() == null) {
                table.setHtmlContent(convertJsonToHtml(table));
            }
        } else {
            String errorMsg = root.has("message") ? root.get("message").asText() : "Unknown error";
            log.error("Table parsing API error: {}", errorMsg);
            table.setHtmlContent("<table><tr><td>Table parsing failed: " + errorMsg + "</td></tr></table>");
        }
    }

    private String convertJsonToHtml(TableContent table) {
        if (table.getJsonContent() == null || table.getJsonContent().isBlank()) {
            return "<table><tr><td>No table data</td></tr></table>";
        }

        try {
            JsonNode jsonData = objectMapper.readTree(table.getJsonContent());
            StringBuilder html = new StringBuilder("<table>");

            if (jsonData.isArray()) {
                for (JsonNode row : jsonData) {
                    html.append("<tr>");
                    if (row.isArray()) {
                        for (JsonNode cell : row) {
                            html.append("<td>").append(escapeHtml(cell.asText())).append("</td>");
                        }
                    } else if (row.isObject()) {
                        row.fieldNames().forEachRemaining(field ->
                                html.append("<td>").append(escapeHtml(field)).append("</td>")
                        );
                    }
                    html.append("</tr>");
                }
            }

            html.append("</table>");
            return html.toString();
        } catch (Exception e) {
            log.error("Failed to convert JSON to HTML: {}", e.getMessage());
            return "<table><tr><td>Table conversion failed</td></tr></table>";
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public boolean isEnabled() {
        return extractionConfig.isEnabled() && tableConfig.isEnabled();
    }
}