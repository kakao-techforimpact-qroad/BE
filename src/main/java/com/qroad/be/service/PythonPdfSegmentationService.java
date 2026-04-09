package com.qroad.be.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qroad.be.pdf.PdfArticle;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class PythonPdfSegmentationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long DEFAULT_PROCESS_TIMEOUT_SECONDS = 300L;
    private static final long MIN_PROCESS_TIMEOUT_SECONDS = 60L;
    private static final String DEFAULT_PYTHON_CMD = "python";
    private static final String DEFAULT_SCRIPT_PATH = "scripts/pdf_segmenter/main.py";

    @Getter
    public static class SegmentationOutput {
        private final String paperContent;
        private final List<PdfArticle> articles;

        public SegmentationOutput(String paperContent, List<PdfArticle> articles) {
            this.paperContent = paperContent;
            this.articles = articles;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class SegmentedArticleJson {
        @JsonProperty("article_id")
        public String articleId;
        @JsonProperty("page_start")
        public Integer pageStart;
        public String title;
        public String subtitle;
        public String body;
        public String reporter;
        public String email;
    }

    public SegmentationOutput segment(byte[] pdfBytes, String sourceKey) {
        return segment(pdfBytes, sourceKey, null);
    }

    public SegmentationOutput segment(
            byte[] pdfBytes,
            String sourceKey,
            BiConsumer<Integer, Integer> progressCallback
    ) {
        Path tempDir = null;
        try {
            long timeoutSeconds = resolveTimeoutSeconds();
            Path projectRoot = Path.of("").toAbsolutePath();
            tempDir = Files.createTempDirectory("qroad-pdf-segment-");
            Path inputPdf = tempDir.resolve("input.pdf");
            Files.write(inputPdf, pdfBytes);

            List<String> command = new ArrayList<>();
            command.add(resolvePythonCommand());
            command.add(resolveScriptPath());
            command.add("--pdf");
            command.add(inputPdf.toString());
            command.add("--stdout-json");
            command.add("--progress-stderr");

            if (isDebugEnabled()) {
                Path debugDir = projectRoot.resolve("build")
                        .resolve("pdf-segmenter-debug")
                        .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
                command.add("--debug-dir");
                command.add(debugDir.toString());
                command.add("--dump-pages");
            }

            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(projectRoot.toFile());
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "UTF-8");

            log.info("파이썬 PDF 기사 분리 시작: sourceKey={}, bytes={}, timeoutSeconds={}",
                    sourceKey, pdfBytes.length, timeoutSeconds);
            Process process = pb.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            StringBuilder stderrBuffer = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(2);

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        stdoutBuffer.append(line);
                    }
                } catch (IOException ignored) {
                } finally {
                    latch.countDown();
                }
            }, "pdf-segmenter-stdout-reader");

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        stderrBuffer.append(line).append('\n');
                        parseAndEmitProgress(line, progressCallback);
                    }
                } catch (IOException ignored) {
                } finally {
                    latch.countDown();
                }
            }, "pdf-segmenter-stderr-reader");

            stdoutThread.start();
            stderrThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            latch.await(5, TimeUnit.SECONDS);

            String stdout = stdoutBuffer.toString();
            String stderr = stderrBuffer.toString();

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("파이썬 기사 분리가 시간 초과되었습니다. timeoutSeconds="
                        + timeoutSeconds + ", stderrTail=" + tail(stderr, 1200));
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("파이썬 기사 분리에 실패했습니다. stderr=" + stderr + ", stdout=" + stdout);
            }
            log.info("파이썬 PDF 기사 분리 완료: sourceKey={}, stdoutLength={}", sourceKey, stdout.length());

            List<SegmentedArticleJson> parsed = OBJECT_MAPPER.readValue(
                    stdout,
                    new TypeReference<>() {
                    });

            List<PdfArticle> articles = new ArrayList<>();
            for (SegmentedArticleJson node : parsed) {
                String title = repairMojibake(node.title != null ? node.title.trim() : "");
                String body = repairMojibake(buildBody(node.subtitle, node.body));
                if (!StringUtils.hasText(body)) {
                    continue;
                }
                PdfArticle article = new PdfArticle();
                article.setId(StringUtils.hasText(node.articleId) ? node.articleId : "seg_" + (articles.size() + 1));
                article.setPage(node.pageStart != null ? node.pageStart : 1);
                article.setTitle(title);
                article.setText(body);
                article.setReporter(repairMojibake(node.reporter != null ? node.reporter.trim() : ""));
                article.setEmail(repairMojibake(node.email != null ? node.email.trim() : ""));
                articles.add(article);
            }

            String paperContent = buildPaperContent(articles);
            return new SegmentationOutput(paperContent, articles);

        } catch (Exception e) {
            throw new RuntimeException("파이썬 PDF 기사 분리 처리 중 오류가 발생했습니다.", e);
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    private void parseAndEmitProgress(String line, BiConsumer<Integer, Integer> progressCallback) {
        if (progressCallback == null || line == null) {
            return;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("PROGRESS ")) {
            return;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length != 3) {
            return;
        }
        try {
            int processed = Integer.parseInt(parts[1]);
            int total = Integer.parseInt(parts[2]);
            progressCallback.accept(processed, total);
        } catch (NumberFormatException ignored) {
        }
    }

    private String resolvePythonCommand() {
        String fromEnv = System.getenv("PDF_SEGMENTER_PYTHON");
        if (StringUtils.hasText(fromEnv)) {
            return fromEnv.trim();
        }
        return DEFAULT_PYTHON_CMD;
    }

    private String resolveScriptPath() {
        String fromEnv = System.getenv("PDF_SEGMENTER_SCRIPT");
        if (StringUtils.hasText(fromEnv)) {
            return fromEnv.trim();
        }
        return DEFAULT_SCRIPT_PATH;
    }

    private boolean isDebugEnabled() {
        String fromEnv = System.getenv("PDF_SEGMENTER_DEBUG");
        return "1".equals(fromEnv) || "true".equalsIgnoreCase(fromEnv);
    }

    private long resolveTimeoutSeconds() {
        String configured = System.getenv("PDF_SEGMENTER_TIMEOUT_SECONDS");
        if (!StringUtils.hasText(configured)) {
            configured = System.getProperty("pdf.segmenter.timeout-seconds");
        }
        if (!StringUtils.hasText(configured)) {
            return DEFAULT_PROCESS_TIMEOUT_SECONDS;
        }
        try {
            long parsed = Long.parseLong(configured.trim());
            return Math.max(parsed, MIN_PROCESS_TIMEOUT_SECONDS);
        } catch (NumberFormatException e) {
            log.warn("PDF 분리 타임아웃 설정값이 올바르지 않습니다: '{}'. 기본값 {}초를 사용합니다.", configured, DEFAULT_PROCESS_TIMEOUT_SECONDS);
            return DEFAULT_PROCESS_TIMEOUT_SECONDS;
        }
    }

    private String tail(String s, int maxLen) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(s.length() - maxLen);
    }

    private String buildBody(String subtitle, String body) {
        String cleanSubtitle = subtitle != null ? subtitle.trim() : "";
        String cleanBody = body != null ? body.trim() : "";
        if (StringUtils.hasText(cleanSubtitle) && StringUtils.hasText(cleanBody)) {
            return cleanSubtitle + "\n" + cleanBody;
        }
        if (StringUtils.hasText(cleanBody)) {
            return cleanBody;
        }
        return cleanSubtitle;
    }

    private String repairMojibake(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String best = text;
        int bestScore = scoreText(text);

        String cp949Candidate = tryReDecode(text, Charset.forName("MS949"));
        if (cp949Candidate != null) {
            int score = scoreText(cp949Candidate);
            if (score >= bestScore + 3) {
                best = cp949Candidate;
                bestScore = score;
            }
        }

        String latinCandidate = tryReDecode(text, StandardCharsets.ISO_8859_1);
        if (latinCandidate != null) {
            int score = scoreText(latinCandidate);
            if (score >= bestScore + 3) {
                best = latinCandidate;
            }
        }

        return best;
    }

    private String tryReDecode(String text, Charset sourceEncoding) {
        try {
            return new String(text.getBytes(sourceEncoding), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int scoreText(String text) {
        int hangul = 0;
        int cjk = 0;
        int replacement = 0;
        int latinNoise = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\uAC00' && c <= '\uD7A3') {
                hangul++;
            } else if ((c >= '\u4E00' && c <= '\u9FFF') || (c >= '\uF900' && c <= '\uFAFF')) {
                cjk++;
            }
            if (c == '\uFFFD') {
                replacement++;
            }
            if ("ÃÂÐØÅÆ".indexOf(c) >= 0) {
                latinNoise++;
            }
        }
        return (hangul * 3) - (cjk * 2) - (replacement * 5) - latinNoise;
    }

    private String buildPaperContent(List<PdfArticle> articles) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < articles.size(); i++) {
            PdfArticle a = articles.get(i);
            sb.append("=".repeat(60)).append("\n");
            sb.append(String.format("[기사 %d] 페이지 %d | %s%n", i + 1, a.getPage(), a.getId()));
            sb.append("=".repeat(60)).append("\n");
            sb.append("제목: ").append(a.getTitle() != null ? a.getTitle().trim() : "").append("\n\n");
            sb.append(a.getText() != null ? a.getText().trim() : "").append("\n\n\n");
        }
        return sb.toString();
    }

    private void deleteRecursively(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
