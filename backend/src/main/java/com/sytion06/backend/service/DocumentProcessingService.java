package com.sytion06.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.ResponseFormatJsonObject;
import com.sytion06.backend.model.Document;
import com.sytion06.backend.model.DocumentStatus;
import com.sytion06.backend.model.Question;
import com.sytion06.backend.repo.DocumentRepository;
import com.sytion06.backend.repo.QuestionRepository;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.*;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class DocumentProcessingService {

    private final DocumentRepository documents;
    private final QuestionRepository questions;
    private final ObjectMapper om = new ObjectMapper();
    private final OpenAIClient client = OpenAIOkHttpClient.fromEnv();

    public DocumentProcessingService(DocumentRepository documents, QuestionRepository questions) {
        this.documents = documents;
        this.questions = questions;
    }

    @Transactional(rollbackFor = Exception.class)
    public void process(UUID docId) throws Exception {
        Document doc = documents.findById(docId).orElseThrow();

        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setLastError(null);
        questions.deleteByDocumentId(docId);
        documents.save(doc);

        int totalSaved = 0;

        try {
            Path pdfPath = Paths.get("storage").resolve(docId + ".pdf");
            if (!Files.exists(pdfPath)) {
                throw new FileNotFoundException(pdfPath.toString());
            }

            Path pagesDir = Paths.get("storage").resolve(docId.toString()).resolve("pages");
            Files.createDirectories(pagesDir);

            try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
                PDFRenderer renderer = new PDFRenderer(pdf);
                int pageCount = pdf.getNumberOfPages();

                for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                    try {
                        String pageText = extractText(pdf, pageIndex);
                        if (looksLikeAnswerKeyStart(pageText)) break;

                        Path pagePng = pagesDir.resolve(String.format("p%03d.png", pageIndex + 1));
                        if (!Files.exists(pagePng)) {
                            BufferedImage img = renderer.renderImageWithDPI(pageIndex, 150);
                            ImageIO.write(img, "png", pagePng.toFile());
                        }

                        List<Question> extracted = extractWithRetry(docId, pageIndex, pageText, pagePng);

                        if (extracted != null && !extracted.isEmpty()) {
                            questions.saveAll(extracted);
                            totalSaved += extracted.size();
                        }

                    } catch (Exception pageErr) {
                        // ✅ log and continue so one bad page doesn't fail the whole doc
                        pageErr.printStackTrace();

                        // Optional: also append to doc.lastError but keep going
                        doc.setLastError("Page " + (pageIndex + 1) + " failed: " + pageErr.getMessage());
                        documents.save(doc);
                    }
                }
            }

            if (totalSaved == 0) {
                doc.setStatus(DocumentStatus.FAILED);
                doc.setLastError("No questions extracted.");
            } else {
                doc.setStatus(DocumentStatus.DONE);
                doc.setLastError(null);
            }
            documents.save(doc);

        } catch (Exception e) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setLastError(e.getMessage());
            documents.save(doc);
            throw e;
        }
    }

    private String extractText(PDDocument pdf, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(pdf);
    }

    private boolean looksLikeAnswerKeyStart(String text) {
        if (text == null) return false;
        String t = text.replaceAll("\\s+", "");
        return t.contains("解析版") || t.contains("参考答案") || t.contains("答案") || t.contains("解析") || t.contains("Solutions") || t.contains("Answer");
    }

    private List<Question> extractQuestionsWithOpenAI(UUID docId, int pageIndex, String pageText, Path pagePng) throws Exception {
        // Base64 image
        byte[] bytes = Files.readAllBytes(pagePng);
        String b64 = Base64.getEncoder().encodeToString(bytes);

        String instruction =
                "You are extracting math exam questions from ONE PAGE.\n" +
                        "Return ONLY JSON with this schema:\n" +
                        "{ \"questions\": [\n" +
                        "  {\n" +
                        "    \"numberLabel\": \"string\",\n" +
                        "    \"stem\": \"string\",\n" +
                        "    \"choices\": {\"A\":\"...\",\"B\":\"...\",\"C\":\"...\",\"D\":\"...\"} | null,\n" +
                        "    \"category\": \"Algebra|Trigonometry|Geometry|Vectors|Probability|Calculus|Sequences|Functions|Set Theory|Other\",\n" +
                        "    \"confidence\": 0.0,\n" +
                        "    \"needsReview\": true|false,\n" +
                        "    \"reviewReason\": \"string or null\"\n" +
                        "    \"hasFigure\": true|false,\n" +
                        "  }\n" +
                        "] }\n" +
                        "Rules:\n" +
                        "- If the page is blurry / unreadable, set needsReview=true and lower confidence.\n" +
                        "- Ignore solution/explanations if present.\n" +
                        "- Keep math expressions readable in plain text (use standard symbols).\n";

        // Responses API with text + image (vision) :contentReference[oaicite:6]{index=6}
        List<ResponseInputItem> items = List.of(
                ResponseInputItem.ofMessage(
                        ResponseInputItem.Message.builder()
                                .role(ResponseInputItem.Message.Role.USER)
                                .addContent(ResponseInputText.builder()
                                        .text(instruction + "\n\nExtracted text (may be empty):\n" + pageText)
                                        .build())
                                .addContent(ResponseInputImage.builder()
                                        .imageUrl("data:image/png;base64," + b64)
                                        .detail(ResponseInputImage.Detail.AUTO)
                                        .build())
                                .build()
                )
        );

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model("gpt-5.2")
                .inputOfResponse(items)   // ✅ convenience alias
                .build();

        Response resp = client.responses().create(params);

        String json = extractOutputTextJsonSafe(resp);

        saveRawResponse(docId, pageIndex, json);

        JsonNode root = om.readTree(json);
        JsonNode arr = root.get("questions");
        if (arr == null || !arr.isArray()) return List.of();

        List<Question> out = new ArrayList<>();
        for (JsonNode q : arr) {
            Question entity = new Question();
            entity.setDocumentId(docId);
            entity.setPageIndex(pageIndex);
            entity.setNumberLabel(q.path("numberLabel").asText(""));
            entity.setStem(q.path("stem").asText(""));
            entity.setCategory(q.path("category").asText("Other"));
            entity.setConfidence(q.path("confidence").asDouble(0.0));
            entity.setNeedsReview(q.path("needsReview").asBoolean(false));
            entity.setReviewReason(q.path("reviewReason").isNull() ? null : q.path("reviewReason").asText(null));
            entity.setHasFigure(q.path("hasFigure").asBoolean(false));
            entity.setPageImageFile(String.format("p%03d.png", pageIndex + 1));

            JsonNode choices = q.get("choices");
            entity.setChoicesJson(choices == null || choices.isNull() ? null : choices.toString());

            out.add(entity);
        }
        return out;
    }

    private List<Question> extractWithRetry(UUID docId, int pageIndex, String pageText, Path pagePng) throws Exception {
        int maxAttempts = 3;
        long backoffMs = 500;

        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return extractQuestionsWithOpenAI(docId, pageIndex, pageText, pagePng);
            } catch (Exception e) {
                last = e;

                // save debug info for this failure (super important)
                saveFailureLog(docId, pageIndex, attempt, e);

                if (attempt < maxAttempts) {
                    Thread.sleep(backoffMs);
                    backoffMs *= 2;
                }
            }
        }
        throw last;
    }

    private void saveFailureLog(UUID docId, int pageIndex, int attempt, Exception e) {
        try {
            Path dir = Paths.get("storage")
                    .resolve(docId.toString())
                    .resolve("logs");
            Files.createDirectories(dir);

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path out = dir.resolve(String.format("page_%03d_attempt_%d_%s.txt", pageIndex + 1, attempt, ts));

            String msg = "docId: " + docId + "\n"
                    + "pageIndex: " + pageIndex + " (page " + (pageIndex + 1) + ")\n"
                    + "attempt: " + attempt + "\n"
                    + "exception: " + e.getClass().getName() + "\n"
                    + "message: " + (e.getMessage() == null ? "" : e.getMessage()) + "\n";

            Files.writeString(out, msg, StandardCharsets.UTF_8);

        } catch (Exception ignore) {
            // don't let logging break processing
        }
    }

    /**
     * Optional: save the raw model response so you can debug JSON issues.
     * Call this inside extractQuestionsWithOpenAI right after you receive the response string.
     */
    private void saveRawResponse(UUID docId, int pageIndex, String raw) {
        try {
            Path dir = Paths.get("storage")
                    .resolve(docId.toString())
                    .resolve("raw");
            Files.createDirectories(dir);

            Path out = dir.resolve(String.format("page_%03d_response.json", pageIndex + 1));
            Files.writeString(out, raw == null ? "" : raw, StandardCharsets.UTF_8);

        } catch (Exception ignore) {
        }
    }

    public static String extractOutputTextJsonSafe(Object responseObj) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.valueToTree(responseObj);

        StringBuilder sb = new StringBuilder();

        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {

                // Messages usually look like: { "type":"message", "content":[ ... ] }
                JsonNode content = item.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode c : content) {
                        if ("output_text".equals(c.path("type").asText())) {
                            sb.append(c.path("text").asText(""));
                        }
                    }
                }
            }
        }

        return sb.toString();
    }
}
