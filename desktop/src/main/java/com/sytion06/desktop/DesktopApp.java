package com.sytion06.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import okhttp3.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class DesktopApp extends Application {

    private static final String BASE_URL = "http://127.0.0.1:8080";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(60))
            .readTimeout(Duration.ofSeconds(60))
            .callTimeout(Duration.ofSeconds(90))
            .build();

    // --- Upload tab state
    private File selectedPdf;
    private Label selectedFileLabel;
    private Button uploadButton;
    private ProgressIndicator uploadProgress;
    private TextArea uploadOutput;

    // --- Documents tab state
    private final ObservableList<DocumentRow> documents = FXCollections.observableArrayList();
    private TableView<DocumentRow> table;
    private TextArea docsOutput;

    private ScheduledExecutorService poller;

    private final ObservableList<QuestionRow> questionsList = FXCollections.observableArrayList();
    private TableView<QuestionRow> qTable;
    private TextArea qPreview;
    private ImageView qImageView;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void start(Stage stage) {
        stage.setTitle("Question Bank");

        var tabs = new TabPane();
        tabs.getTabs().add(new Tab("Upload", buildUploadTab(stage)));
        tabs.getTabs().add(new Tab("Documents", buildDocumentsTab()));
        tabs.getTabs().add(new Tab("Questions", buildQuestionsTab()));

        tabs.getTabs().forEach(t -> t.setClosable(false));

        stage.setScene(new Scene(tabs, 1200, 800));
        stage.show();

        // initial load
        refreshDocuments();
        startPolling();
    }

    // ---------------- Upload Tab ----------------
    private Pane buildUploadTab(Stage stage) {
        var title = new Label("Upload a PDF (Past Exams / Problem Sets)");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        selectedFileLabel = new Label("No file selected.");
        selectedFileLabel.setWrapText(true);

        var chooseButton = new Button("Choose PDF…");
        chooseButton.setOnAction(e -> choosePdf(stage));

        uploadButton = new Button("Upload");
        uploadButton.setDisable(true);
        uploadButton.setOnAction(e -> uploadSelectedPdf());

        uploadProgress = new ProgressIndicator();
        uploadProgress.setVisible(false);
        uploadProgress.setPrefSize(24, 24);

        var topRow = new HBox(10, chooseButton, uploadButton, uploadProgress);
        topRow.setPadding(new Insets(10, 0, 0, 0));

        uploadOutput = new TextArea();
        uploadOutput.setEditable(false);
        uploadOutput.setWrapText(true);

        var root = new VBox(12,
                title,
                selectedFileLabel,
                topRow,
                new Label("Result:"),
                uploadOutput
        );
        root.setPadding(new Insets(16));
        return root;
    }

    private void choosePdf(Stage stage) {
        var chooser = new FileChooser();
        chooser.setTitle("Select a PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files (*.pdf)", "*.pdf"));

        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        selectedPdf = file;
        selectedFileLabel.setText("Selected: " + file.getAbsolutePath());
        uploadButton.setDisable(false);
        uploadOutput.clear();
    }

    private void setUploading(boolean uploading) {
        uploadProgress.setVisible(uploading);
        uploadButton.setDisable(uploading || selectedPdf == null);
    }

    private void uploadSelectedPdf() {
        if (selectedPdf == null) return;

        setUploading(true);
        uploadOutput.setText("Uploading...\n");

        Thread worker = new Thread(() -> {
            try {
                String json = uploadPdfMultipart(selectedPdf);
                Platform.runLater(() -> {
                    uploadOutput.appendText("✅ Upload success!\n" + json + "\n");
                    setUploading(false);
                    refreshDocuments(); // update documents list
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    uploadOutput.appendText("❌ Upload failed:\n" + ex.getMessage() + "\n");
                    setUploading(false);
                });
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private String uploadPdfMultipart(File pdf) throws IOException {
        MediaType PDF = MediaType.parse("application/pdf");
        RequestBody fileBody = RequestBody.create(pdf, PDF);

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", pdf.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(BASE_URL + "/api/documents")
                .post(requestBody)
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + ": " + body);
            return body;
        }
    }

    // ---------------- Documents Tab ----------------
    private Pane buildDocumentsTab() {
        table = new TableView<>(documents);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<DocumentRow, String> filename = new TableColumn<>("Filename");
        filename.setCellValueFactory(new PropertyValueFactory<>("filename"));

        TableColumn<DocumentRow, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(new PropertyValueFactory<>("status"));

        TableColumn<DocumentRow, String> createdAt = new TableColumn<>("Created");
        createdAt.setCellValueFactory(new PropertyValueFactory<>("createdAt"));

        TableColumn<DocumentRow, String> docId = new TableColumn<>("Doc ID");
        docId.setCellValueFactory(new PropertyValueFactory<>("docId"));

        table.getColumns().addAll(filename, status, createdAt, docId);

        var refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> refreshDocuments());

        var processBtn = new Button("Process selected");
        processBtn.setOnAction(e -> processSelected());

        var buttons = new HBox(10, refreshBtn, processBtn);

        docsOutput = new TextArea();
        docsOutput.setEditable(false);
        docsOutput.setWrapText(true);

        var root = new VBox(10,
                new Label("Documents"),
                buttons,
                table,
                new Label("Log:"),
                docsOutput
        );
        root.setPadding(new Insets(16));
        VBox.setVgrow(table, Priority.ALWAYS);
        return root;
    }

    private void refreshDocuments() {
        Thread worker = new Thread(() -> {
            try {
                List<DocumentRow> rows = fetchDocuments();
                Platform.runLater(() -> {
                    documents.setAll(rows);
                    docsOutput.appendText("Refreshed: " + rows.size() + " documents\n");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> docsOutput.appendText("❌ Refresh failed: " + ex.getMessage() + "\n"));
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void processSelected() {
        DocumentRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            docsOutput.appendText("Select a document first.\n");
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                String resp = postProcess(sel.getDocId());
                Platform.runLater(() -> {
                    docsOutput.appendText("Process started: " + resp + "\n");
                    refreshDocuments();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> docsOutput.appendText("❌ Process failed: " + ex.getMessage() + "\n"));
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private List<DocumentRow> fetchDocuments() throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/documents")
                .get()
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + ": " + body);

            // Minimal JSON parsing without adding a JSON library:
            // This is intentionally basic for MVP.
            // Expected format: [ { "docId":"..", "filename":"..", "status":"..", "createdAt":".." }, ... ]
            return SimpleJson.parseDocumentList(body);
        }
    }

    private String postProcess(String docId) throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/documents/" + docId + "/process")
                .post(RequestBody.create(new byte[0], null))
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + ": " + body);
            return body;
        }
    }

    // Poll status every 2s to keep the table updated during processing
    private void startPolling() {
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "doc-poller");
            t.setDaemon(true);
            return t;
        });

        poller.scheduleAtFixedRate(() -> {
            boolean anyProcessing = documents.stream().anyMatch(d -> "PROCESSING".equals(d.getStatus()));
            if (anyProcessing) {
                try {
                    List<DocumentRow> rows = fetchDocuments();
                    Platform.runLater(() -> documents.setAll(rows));
                } catch (Exception ignored) {
                }
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private Pane buildQuestionsTab() {
        // --- table (LEFT)
        qTable = new TableView<>(questionsList);
        qTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<QuestionRow, String> number = new TableColumn<>("No.");
        number.setCellValueFactory(new PropertyValueFactory<>("numberLabel"));

        TableColumn<QuestionRow, String> cat = new TableColumn<>("Category");
        cat.setCellValueFactory(new PropertyValueFactory<>("category"));

        TableColumn<QuestionRow, Number> page = new TableColumn<>("Page");
        page.setCellValueFactory(new PropertyValueFactory<>("pageIndex"));

        TableColumn<QuestionRow, Number> conf = new TableColumn<>("Conf");
        conf.setCellValueFactory(new PropertyValueFactory<>("confidence"));

        TableColumn<QuestionRow, Boolean> review = new TableColumn<>("Review?");
        review.setCellValueFactory(new PropertyValueFactory<>("needsReview"));

        qTable.getColumns().addAll(number, cat, page, conf, review);

        // --- text preview (RIGHT top)
        qPreview = new TextArea();
        qPreview.setEditable(false);
        qPreview.setWrapText(true);
        qPreview.setPrefRowCount(18);
        qPreview.setPromptText("Select a question to preview...");

        // --- image preview (RIGHT bottom)
        qImageView = new ImageView();
        qImageView.setPreserveRatio(true);
        qImageView.setFitWidth(900);

        ScrollPane imgScroll = new ScrollPane(qImageView);
        imgScroll.setFitToWidth(true);
        imgScroll.setPrefViewportHeight(520);

        VBox previewBox = new VBox(10,
                new Label("Preview:"),
                qPreview,
                new Label("Figure / Page:"),
                imgScroll
        );
        previewBox.setPadding(new Insets(10));
        VBox.setVgrow(imgScroll, Priority.ALWAYS);

        // --- selection listener
        qTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) {
                qPreview.clear();
                qImageView.setImage(null);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(newV.getStem()).append("\n\n");

            var choices = newV.getChoices();
            if (choices != null && !choices.isEmpty()) {
                sb.append("Choices:\n");
                for (String key : List.of("A", "B", "C", "D", "E")) {
                    String val = choices.get(key);
                    if (val != null && !val.isBlank()) {
                        sb.append(key).append(". ").append(val).append("\n");
                    }
                }
            }
            qPreview.setText(sb.toString());

            // Load image if available
            if (newV.getPageImageUrl() != null && !newV.getPageImageUrl().isBlank()) {
                String url = BASE_URL + newV.getPageImageUrl();
                qImageView.setImage(new javafx.scene.image.Image(url, true));
            } else {
                qImageView.setImage(null);
            }
        });

        Button loadBtn = new Button("Load questions for selected document");
        loadBtn.setOnAction(e -> loadQuestionsForSelectedDoc());

        SplitPane split = new SplitPane(qTable, previewBox);
        split.setDividerPositions(0.40);

        var root = new VBox(10, loadBtn, split);
        root.setPadding(new Insets(16));
        VBox.setVgrow(split, Priority.ALWAYS);
        return root;
    }

    private void loadQuestionsForSelectedDoc() {
        DocumentRow sel = table.getSelectionModel().getSelectedItem(); // the documents table
        if (sel == null) {
            docsOutput.appendText("Select a document in the Documents tab first.\n");
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                String json = getQuestionsJson(sel.getDocId());
                List<QuestionRow> rows = SimpleJson.parseQuestionList(json);
                Platform.runLater(() -> {
                    questionsList.setAll(rows);
                    qPreview.clear();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> docsOutput.appendText("❌ Load questions failed: " + ex.getMessage() + "\n"));
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private String getQuestionsJson(String docId) throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/documents/" + docId + "/questions")
                .get()
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + ": " + body);
            return body;
        }
    }

    @Override
    public void stop() {
        if (poller != null) poller.shutdownNow();
    }

    public static class QuestionRow {
        private final String id;
        private final String documentId;
        private final int pageIndex;
        private final String numberLabel;
        private final String category;
        private final double confidence;
        private final boolean needsReview;
        private final String stem;
        private final Map<String, String> choices;
        private final boolean hasFigure;
        private final String pageImageUrl;

        public QuestionRow(String id, String documentId, int pageIndex, String numberLabel,
                           String category, double confidence, boolean needsReview,
                           String stem, Map<String, String> choices, boolean hasFigure, String pageImageUrl) {
            this.id = id;
            this.documentId = documentId;
            this.pageIndex = pageIndex;
            this.numberLabel = numberLabel;
            this.category = category;
            this.confidence = confidence;
            this.needsReview = needsReview;
            this.stem = stem;
            this.choices = choices;
            this.hasFigure = hasFigure;
            this.pageImageUrl = pageImageUrl;
        }

        public String getId() { return id; }
        public String getDocumentId() { return documentId; }
        public int getPageIndex() { return pageIndex; }
        public String getNumberLabel() { return numberLabel; }
        public String getCategory() { return category; }
        public double getConfidence() { return confidence; }
        public boolean isNeedsReview() { return needsReview; }
        public String getStem() { return stem; }
        public Map<String, String> getChoices() {
            return choices;
        }
        public boolean isHasFigure() { return hasFigure; }
        public String getPageImageUrl() { return pageImageUrl; }
    }

    // --- Table row model
    public static class DocumentRow {
        private final String docId;
        private final String filename;
        private final String status;
        private final String createdAt;

        public DocumentRow(String docId, String filename, String status, String createdAt) {
            this.docId = docId;
            this.filename = filename;
            this.status = status;
            this.createdAt = createdAt;
        }

        public String getDocId() { return docId; }
        public String getFilename() { return filename; }
        public String getStatus() { return status; }
        public String getCreatedAt() { return createdAt; }
    }

    // --- Super tiny JSON helper for MVP (no new deps yet)
    // If you prefer, next step we’ll replace this with Jackson (cleaner).
    static class SimpleJson {
        static List<DocumentRow> parseDocumentList(String json) {
            // Very small/naive parser for known shape.
            // Assumes no escaped quotes inside fields.
            List<DocumentRow> out = new ArrayList<>();
            String trimmed = json.trim();
            if (trimmed.length() < 2 || trimmed.equals("[]")) return out;

            // split objects: "},{"
            String body = trimmed.substring(1, trimmed.length() - 1).trim();
            String[] objs = body.split("\\},\\s*\\{");
            for (String raw : objs) {
                String obj = raw;
                if (!obj.startsWith("{")) obj = "{" + obj;
                if (!obj.endsWith("}")) obj = obj + "}";

                String docId = getString(obj, "docId");
                String filename = getString(obj, "filename");
                String status = getString(obj, "status");
                String createdAt = getString(obj, "createdAt");
                out.add(new DocumentRow(docId, filename, status, createdAt));
            }
            return out;
        }

        static String getString(String obj, String key) {
            String pattern = "\"" + key + "\"";
            int i = obj.indexOf(pattern);
            if (i < 0) return "";
            int colon = obj.indexOf(":", i);
            int firstQuote = obj.indexOf("\"", colon + 1);
            int secondQuote = obj.indexOf("\"", firstQuote + 1);
            if (firstQuote < 0 || secondQuote < 0) return "";
            return obj.substring(firstQuote + 1, secondQuote);
        }

        static List<QuestionRow> parseQuestionList(String json) throws IOException {
            List<Map<String, Object>> raw = MAPPER.readValue(json, new TypeReference<>() {});
            List<QuestionRow> rows = new ArrayList<>();

            for (Map<String, Object> q : raw) {
                String id = String.valueOf(q.get("id"));
                String documentId = String.valueOf(q.get("documentId"));
                int pageIndex = ((Number) q.get("pageIndex")).intValue();
                String numberLabel = (String) q.get("numberLabel");
                String stem = (String) q.get("stem");
                String category = (String) q.get("category");
                double confidence = ((Number) q.get("confidence")).doubleValue();
                boolean needsReview = (Boolean) q.get("needsReview");
                boolean hasFigure = q.get("hasFigure") != null && (Boolean) q.get("hasFigure");
                String pageImageUrl = (String) q.get("pageImageUrl");

                @SuppressWarnings("unchecked")
                Map<String, String> choices = (Map<String, String>) q.get("choices");

                rows.add(new QuestionRow(id, documentId, pageIndex, numberLabel, category,
                        confidence, needsReview, stem, choices, hasFigure, pageImageUrl));
            }
            return rows;
        }

        static int getInt(String obj, String key) {
            String pattern = "\"" + key + "\"";
            int i = obj.indexOf(pattern);
            if (i < 0) return 0;
            int colon = obj.indexOf(":", i);
            int end = obj.indexOf(",", colon + 1);
            if (end < 0) end = obj.indexOf("}", colon + 1);
            String val = obj.substring(colon + 1, end).trim();
            return Integer.parseInt(val);
        }

        static double getDouble(String obj, String key) {
            String pattern = "\"" + key + "\"";
            int i = obj.indexOf(pattern);
            if (i < 0) return 0.0;
            int colon = obj.indexOf(":", i);
            int end = obj.indexOf(",", colon + 1);
            if (end < 0) end = obj.indexOf("}", colon + 1);
            String val = obj.substring(colon + 1, end).trim();
            return Double.parseDouble(val);
        }

        static boolean getBoolean(String obj, String key) {
            String pattern = "\"" + key + "\"";
            int i = obj.indexOf(pattern);
            if (i < 0) return false;
            int colon = obj.indexOf(":", i);
            int end = obj.indexOf(",", colon + 1);
            if (end < 0) end = obj.indexOf("}", colon + 1);
            String val = obj.substring(colon + 1, end).trim();
            return "true".equalsIgnoreCase(val);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
