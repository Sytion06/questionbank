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
import java.nio.file.*;
import java.util.*;

@Service
public class DocumentProcessingService {

    private final DocumentRepository documents;
    private final QuestionRepository questions;
    private final ObjectMapper om = new ObjectMapper();
    private final OpenAIClient client = OpenAIOkHttpClient.builder().apiKey("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")
            .build();// reads OPENAI_API_KEY

    public DocumentProcessingService(DocumentRepository documents, QuestionRepository questions) {
        this.documents = documents;
        this.questions = questions;
    }

    @Transactional
    public void process(UUID docId) throws Exception {
        Document doc = documents.findById(docId).orElseThrow();
        doc.setStatus(DocumentStatus.PROCESSING);
        documents.save(doc);

        Path pdfPath = Paths.get("storage").resolve(docId + ".pdf");
        if (!Files.exists(pdfPath)) throw new FileNotFoundException(pdfPath.toString());

        // Store rendered page images so JavaFX can show them later
        Path pagesDir = Paths.get("storage").resolve(docId.toString()).resolve("pages");
        Files.createDirectories(pagesDir);
        try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            int pageCount = pdf.getNumberOfPages();

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                String pageText = extractText(pdf, pageIndex);
                if (looksLikeAnswerKeyStart(pageText)) {
                    // stop when reaching 解析/答案 section (your 2024 pdf includes “解析版” later :contentReference[oaicite:5]{index=5})
                    break;
                }

                Path pagePng = pagesDir.resolve(String.format("p%03d.png", pageIndex + 1));
                if (!Files.exists(pagePng)) {
                    BufferedImage img = renderer.renderImageWithDPI(pageIndex, 150);
                    ImageIO.write(img, "png", pagePng.toFile());
                }

                // Call OpenAI to extract questions from this page
                List<Question> extracted = extractQuestionsWithOpenAI(docId, pageIndex, pageText, pagePng);
                questions.saveAll(extracted);
            }
        }

        doc.setStatus(DocumentStatus.DONE);
        documents.save(doc);
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
        return t.contains("解析版") || t.contains("参考答案") || t.contains("答案") || t.contains("解析");
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
                .model("gpt-4.1-mini")
                .inputOfResponse(items)   // ✅ convenience alias
                .build();

        Response resp = client.responses().create(params);

        String json = extractOutputTextJsonSafe(resp);

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

            JsonNode choices = q.get("choices");
            entity.setChoicesJson(choices == null || choices.isNull() ? null : choices.toString());

            out.add(entity);
        }
        return out;
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
