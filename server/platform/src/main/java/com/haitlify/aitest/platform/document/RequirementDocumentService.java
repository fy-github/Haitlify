package com.haitlify.aitest.platform.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;

@Service
public class RequirementDocumentService {

    private static final Pattern PROJECT_PATTERN = Pattern.compile("^项目[:：]\\s*(.+)$", Pattern.MULTILINE);
    private final boolean ocrAvailable = detectOcrAvailability();

    public RequirementDocumentParseResponse parse(MultipartFile file) {
        String fileName = Optional.ofNullable(file.getOriginalFilename())
            .filter(name -> !name.isBlank())
            .orElse("requirement");
        String documentType = documentTypeFromFileName(fileName);
        String extractedText = extractText(file, fileName);
        String normalizedText = decorateParsedText(documentType, fileName, normalizeText(extractedText));
        String projectName = extractProjectName(normalizedText);
        RequirementVisualSummary visualSummary = buildVisualSummary(file, documentType, normalizedText);
        List<String> suggestedRequirementPoints = buildSuggestedRequirementPoints(documentType, visualSummary);
        // T08：逐来源状态与 warnings（正文容器内图表、无文本层、OCR 不可用一律显式）。
        int[] containerCounts = countContainerElements(file, fileName);
        int tableCount = containerCounts[0];
        int imageCount = containerCounts[1];
        String rawText = extractedText == null ? "" : extractedText.trim();
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (rawText.isBlank() && "image".equals(documentType)) {
            warnings.add(ocrAvailable
                ? "image OCR produced no usable text; confirm image clarity or attach notes manually"
                : "image OCR unavailable (tesseract not installed); manual supplement required");
        } else if (rawText.isBlank()) {
            warnings.add("no extractable text layer (scanned image or unsupported container)");
        }
        if (imageCount > 0) {
            warnings.add(imageCount + " inline image(s) present; verify visual asset coverage separately");
        }
        String parseStatus;
        if (rawText.isBlank() && imageCount == 0 && tableCount == 0 && !"image".equals(documentType)) {
            parseStatus = "UNSUPPORTED";
        } else if (rawText.isBlank() || !warnings.isEmpty()) {
            parseStatus = "PARTIAL";
        } else {
            parseStatus = "AVAILABLE";
        }

        return new RequirementDocumentParseResponse(
            fileName,
            documentType,
            projectName,
            normalizedText,
            countLines(normalizedText),
            visualSummary,
            suggestedRequirementPoints,
            parseStatus,
            java.util.List.copyOf(warnings),
            tableCount,
            imageCount
        );
    }

    /**
     * OOXML 容器的表格/图片计数（docx/xlsx/pptx 为 zip；损坏时静默返回 0/0——由文本层
     * 状态兜底，绝不因计数失败拒绝解析）。
     */
    private int[] countContainerElements(MultipartFile file, String fileName) {
        String extension = extensionOf(fileName);
        if (!"docx".equals(extension) && !"xlsx".equals(extension) && !"pptx".equals(extension)) {
            return new int[] {0, 0};
        }
        int tables = 0;
        int images = 0;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(file.getInputStream())) {
            byte[] buffer = new byte[8192];
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                boolean relevant = name.endsWith(".xml") || name.endsWith(".rels");
                if (!relevant) {
                    continue;
                }
                StringBuilder content = new StringBuilder();
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    content.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
                String xml = content.toString();
                if ("docx".equals(extension)) {
                    tables += countOccurrences(xml, "<w:tbl");
                    images += countOccurrences(xml, "<w:drawing") + countOccurrences(xml, "<w:pict");
                } else if ("xlsx".equals(extension)) {
                    tables += countOccurrences(xml, "<worksheet");
                    images += countOccurrences(xml, "<drawing");
                } else {
                    tables += countOccurrences(xml, "<p:graphicFrame");
                    images += countOccurrences(xml, "<p:pic");
                }
            }
        } catch (Exception unreadable) {
            return new int[] {0, 0};
        }
        return new int[] {tables, images};
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int index = content.indexOf(needle);
        while (index >= 0) {
            count++;
            index = content.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private String extractText(MultipartFile file, String fileName) {
        String extension = extensionOf(fileName);
        if (isUtf8TextExtension(extension)) {
            try {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalArgumentException("读取需求文件失败: " + fileName, exception);
            }
        }

        try (InputStream inputStream = file.getInputStream()) {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            ContentHandler handler = new BodyContentHandler(-1);
            new AutoDetectParser().parse(inputStream, handler, metadata, new ParseContext());
            return handler.toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("解析需求文件失败: " + fileName, exception);
        }
    }

    private String normalizeText(String text) {
        return text
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim();
    }

    private String decorateParsedText(String documentType, String fileName, String text) {
        if (!text.isBlank()) {
            return text;
        }
        if ("image".equals(documentType)) {
            return ocrAvailable
                ? "图片 OCR 未识别到可用文本，请确认图片内容清晰: " + fileName
                : "图片 OCR 未启用，请安装 tesseract 后重试: " + fileName;
        }
        return text;
    }

    private RequirementVisualSummary buildVisualSummary(MultipartFile file, String documentType, String text) {
        if (!"image".equals(documentType)) {
            return null;
        }
        int width = 0;
        int height = 0;
        try (InputStream inputStream = file.getInputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException ignored) {
            width = 0;
            height = 0;
        }
        double aspectRatio = width > 0 && height > 0 ? ((double) width) / height : 0D;
        String viewportHint = width >= height && width > 0 ? "desktop" : (height > width ? "mobile" : "unknown");
        String ocrStatus = !ocrAvailable ? "unavailable" : (text.isBlank() ? "empty" : "available");

        return new RequirementVisualSummary(
            width,
            height,
            aspectRatio,
            viewportHint,
            List.of(viewportHint.equals("desktop") ? "多栏桌面布局候选" : "单栏移动布局候选"),
            List.of("需校验色彩、间距、层级与设计稿一致"),
            List.of("导航区域", "主内容区", "按钮/表单组件"),
            ocrStatus
        );
    }

    private List<String> buildSuggestedRequirementPoints(String documentType, RequirementVisualSummary visualSummary) {
        if (!"image".equals(documentType) || visualSummary == null) {
            return List.of();
        }
        return List.of(
            "视觉样式与组件间距、对齐、层级符合设计稿",
            ("desktop".equals(visualSummary.viewportHint())
                ? "桌面端主内容区布局稳定，核心操作区域无遮挡"
                : "移动端页面布局稳定，核心操作区域无遮挡")
        );
    }

    private String extractProjectName(String text) {
        Matcher matcher = PROJECT_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "默认项目";
    }

    private int countLines(String text) {
        if (text.isBlank()) return 0;
        return (int) text.lines().count();
    }

    private boolean isUtf8TextExtension(String extension) {
        return extension.equals("txt")
            || extension.equals("md")
            || extension.equals("markdown")
            || extension.equals("json")
            || extension.equals("yaml")
            || extension.equals("yml")
            || extension.equals("csv");
    }

    private String documentTypeFromFileName(String fileName) {
        String extension = extensionOf(fileName);
        return switch (extension) {
            case "pdf" -> "pdf";
            case "doc", "docx" -> "word";
            case "xls", "xlsx" -> "excel";
            case "md", "markdown" -> "markdown";
            case "yaml", "yml" -> "yaml";
            case "json" -> "json";
            case "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "image";
            default -> "text";
        };
    }

    private String extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private boolean detectOcrAvailability() {
        try {
            Process process = new ProcessBuilder("tesseract", "--version").start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception exception) {
            return false;
        }
    }
}
