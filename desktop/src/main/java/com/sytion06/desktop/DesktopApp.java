package com.sytion06.desktop;

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

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class DesktopApp extends Application {

    private static final String BASE_URL = "http://127.0.0.1:8080";
    private static final OkHttpClient HTTP = new OkHttpClient();

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

    @Override
    public void start(Stage stage) {
        stage.setTitle("Gaokao QBank");

        var tabs = new TabPane();
        tabs.getTabs().add(new Tab("Upload", buildUploadTab(stage)));
        tabs.getTabs().add(new Tab("Documents", buildDocumentsTab()));

        tabs.getTabs().forEach(t -> t.setClosable(false));

        stage.setScene(new Scene(tabs, 950, 650));
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

    @Override
    public void stop() {
        if (poller != null) poller.shutdownNow();
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
    }

    public static void main(String[] args) {
        launch(args);
    }
}
