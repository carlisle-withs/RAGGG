package com.rag.domain.chunking;

import org.springframework.stereotype.Component;

/**
 * 文档类型检测器
 * 根据 Tika MIME 类型或文档内容特征检测文档类型
 */
@Component
public class DocumentTypeDetector {

    public enum DocumentType {
        MARKDOWN,      // Markdown 文档
        HTML,          // HTML 文档
        PDF_STRUCTURED, // 结构化 PDF（论文、报告）
        PDF_TEXT,      // 纯文本 PDF
        CODE,          // 代码文件
        JSON,          // JSON 数据
        CSV,           // CSV 表格
        PLAIN_TEXT,    // 纯文本
        DOCX,          // Word 文档
        PPTX,          // PowerPoint 文档
        XLSX,          // Excel 文档
        UNKNOWN         // 未知类型
    }

    // Tika MIME type to DocumentType mapping
    private static final String[][] MIME_TYPE_MAP = {
        // Markdown & Text
        {"text/plain", "PLAIN_TEXT"},
        {"text/markdown", "MARKDOWN"},
        {"text/x-markdown", "MARKDOWN"},

        // HTML
        {"text/html", "HTML"},
        {"application/xhtml+xml", "HTML"},

        // PDF
        {"application/pdf", "PDF_TEXT"},

        // Office documents (Tika detects these after parsing)
        {"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "DOCX"},
        {"application/vnd.openxmlformats-officedocument.presentationml.presentation", "PPTX"},
        {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "XLSX"},

        // Code
        {"text/x-java", "CODE"},
        {"text/x-python", "CODE"},
        {"text/x-c++", "CODE"},
        {"text/x-c", "CODE"},
        {"text/x-java-script", "CODE"},
        {"application/javascript", "CODE"},
        {"text/x-go", "CODE"},

        // Data formats
        {"application/json", "JSON"},
        {"text/csv", "CSV"},
        {"application/xml", "XML"},
        {"text/xml", "XML"},
    };

    /**
     * 根据 Tika MIME 类型检测文档类型（优先使用）
     * @param mimeType Tika 检测到的 MIME 类型
     * @return 文档类型
     */
    public DocumentType detectByMimeType(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return DocumentType.UNKNOWN;
        }

        for (String[] entry : MIME_TYPE_MAP) {
            if (entry[0].equalsIgnoreCase(mimeType)) {
                try {
                    return DocumentType.valueOf(entry[1]);
                } catch (IllegalArgumentException e) {
                    return DocumentType.UNKNOWN;
                }
            }
        }

        // 根据 MIME 类型前缀推断
        if (mimeType.startsWith("text/")) {
            return DocumentType.PLAIN_TEXT;
        }
        if (mimeType.startsWith("application/vnd.openxmlformats-officedocument")) {
            // Office 文档默认走 structural
            return DocumentType.PDF_STRUCTURED;
        }

        return DocumentType.UNKNOWN;
    }

    /**
     * 根据文本内容检测文档类型
     * @param text 文档文本
     * @return 文档类型
     */
    public DocumentType detectByContent(String text) {
        // 检测内容特征
        if (isMarkdown(text)) {
            return DocumentType.MARKDOWN;
        }
        if (isHtml(text)) {
            return DocumentType.HTML;
        }
        if (isJson(text)) {
            return DocumentType.JSON;
        }
        if (isCsv(text)) {
            return DocumentType.CSV;
        }
        if (isCode(text)) {
            return DocumentType.CODE;
        }
        if (isPdfStructured(text)) {
            return DocumentType.PDF_STRUCTURED;
        }

        return DocumentType.PLAIN_TEXT;
    }

    /**
     * 检测文档类型（优先使用 Tika MIME 类型，fallback 到内容检测）
     * @param mimeType Tika 检测到的 MIME 类型（可选）
     * @param text 文档文本
     * @param fileName 文件名（可选，用于辅助判断）
     * @return 文档类型
     */
    public DocumentType detect(String mimeType, String text, String fileName) {
        // 1. 优先使用 Tika MIME 类型
        if (mimeType != null && !mimeType.isEmpty()) {
            DocumentType type = detectByMimeType(mimeType);
            if (type != DocumentType.UNKNOWN) {
                return type;
            }
        }

        // 2. 根据文件名判断
        if (fileName != null && !fileName.isEmpty()) {
            DocumentType type = detectByFileName(fileName);
            if (type != DocumentType.UNKNOWN) {
                return type;
            }
        }

        // 3. 根据内容特征判断
        return detectByContent(text);
    }

    /**
     * 检测文档类型（仅基于文本内容和文件名）
     */
    public DocumentType detect(String text, String fileName) {
        return detect(null, text, fileName);
    }

    private DocumentType detectByFileName(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return DocumentType.MARKDOWN;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return DocumentType.HTML;
        }
        if (lower.endsWith(".pdf")) {
            return DocumentType.PDF_TEXT;
        }
        if (lower.endsWith(".docx")) {
            return DocumentType.DOCX;
        }
        if (lower.endsWith(".pptx")) {
            return DocumentType.PPTX;
        }
        if (lower.endsWith(".xlsx")) {
            return DocumentType.XLSX;
        }
        if (lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js") ||
            lower.endsWith(".ts") || lower.endsWith(".cpp") || lower.endsWith(".c") ||
            lower.endsWith(".go") || lower.endsWith(".rs")) {
            return DocumentType.CODE;
        }
        if (lower.endsWith(".json")) {
            return DocumentType.JSON;
        }
        if (lower.endsWith(".csv")) {
            return DocumentType.CSV;
        }
        if (lower.endsWith(".xml")) {
            return DocumentType.UNKNOWN; // 让内容检测来判断
        }
        return DocumentType.UNKNOWN;
    }

    private boolean isMarkdown(String text) {
        int markdownIndicators = 0;

        if (text.matches("(?m)^#{1,6}\\s+.+$")) markdownIndicators += 3;
        if (text.matches(".*\\[.+?\\]\\(.+?\\).*")) markdownIndicators += 2;
        if (text.matches("(?m)^\\s*[-*+]\\s+.+$")) markdownIndicators += 2;
        if (text.matches("(?m)^\\s*\\d+\\.\\s+.+$")) markdownIndicators += 2;
        if (text.matches("(?m)^>\\s+.+$")) markdownIndicators += 2;
        if (text.contains("```")) markdownIndicators += 2;
        if (text.matches("(?m)^\\s*[-*_]{3,}\\s*$")) markdownIndicators += 1;
        if (text.matches(".*(\\*\\*|\\*).+?(\\*\\*|\\*).*")) markdownIndicators += 1;

        return markdownIndicators >= 4;
    }

    private boolean isHtml(String text) {
        String[] htmlTags = {"<html", "<head", "<body", "<div", "<span", "<p>", "<a ", "<table"};
        int count = 0;
        for (String tag : htmlTags) {
            if (text.toLowerCase().contains(tag.toLowerCase())) {
                count++;
            }
        }
        return count >= 2;
    }

    private boolean isJson(String text) {
        String trimmed = text.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private boolean isCsv(String text) {
        String[] lines = text.split("\n");
        if (lines.length < 2) return false;

        String firstLine = lines[0];
        if (!firstLine.contains(",")) return false;

        String[] cols = firstLine.split(",");
        if (cols.length < 2) return false;

        int expectedCols = cols.length;
        for (int i = 1; i < Math.min(lines.length, 5); i++) {
            if (lines[i].split(",").length != expectedCols) {
                return false;
            }
        }
        return true;
    }

    private boolean isCode(String text) {
        int codeIndicators = 0;

        String[] keywords = {"public", "private", "class", "function", "def ", "import ", "const ", "let ",
                            "var ", "if (", "for (", "while (", "return ", "try {", "catch (", "fn ", "func "};
        for (String kw : keywords) {
            if (text.contains(kw)) codeIndicators++;
        }

        if (text.contains("()")) codeIndicators++;
        if (text.contains("{}")) codeIndicators++;
        if (text.contains("=>")) codeIndicators++;
        if (text.contains(";")) codeIndicators++;

        String[] lines = text.split("\n");
        int indentedLines = 0;
        for (String line : lines) {
            if (line.startsWith("    ") || line.startsWith("\t")) {
                indentedLines++;
            }
        }
        if (indentedLines > lines.length * 0.3) codeIndicators += 2;

        return codeIndicators >= 5;
    }

    private boolean isPdfStructured(String text) {
        int structureIndicators = 0;

        if (text.matches("(?m)^\\d+\\.\\s+.+$")) structureIndicators += 3;
        if (text.matches("(?m)^\\d+\\.\\d+\\.\\s+.+$")) structureIndicators += 3;
        if (text.matches(".*第[一二三四五六七八九十]+章.*")) structureIndicators += 3;
        if (text.matches("(?i).*摘要.*")) structureIndicators += 2;
        if (text.matches("(?i).*参考文献.*")) structureIndicators += 2;
        if (text.matches("(?i).*Abstract.*")) structureIndicators += 2;
        if (text.matches(".*\\[[\\d,\\s-]+\\].*")) structureIndicators += 2;

        return structureIndicators >= 5;
    }
}
