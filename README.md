# Requirements

- Java 21
- Gradle (Gradle Wrapper Included)
- OpenAI API Key
- Internet Connection

---

# Installation

## 1. Clone Repository

```bash
git clone https://github.com/Sytion06/questionbank.git
cd questionbank
```

## 2. Set OpenAI API Key

### Windows (cmd)
```cmd
set OPENAI_API_KEY=your_key_here
```

### Windows (PowerShell)
```powershell
$env:OPENAI_API_KEY="your_key_here"
```

### macOS / Linux
```bash
export OPENAI_API_KEY="your_key_here"
```

---

# Run Backend

```bash
.\gradlew :backend:bootRun
```

Backend runs at:

```
http://127.0.0.1:8080
```

---

# Run Desktop Client

```bash
.\gradlew :desktop:run
```

---

# Usage

### Upload a PDF
1. Go to the **Upload** tab.
2. Click **Choose PDF…** and pick a PDF file.
3. Click **Upload**.
4. After upload succeeds, go to the **Documents** tab and click **Refresh** (if it doesn’t appear immediately).

### Process (Extract Questions)

1. Go to the **Documents** tab.
2. Click the document row you want to process.
3. Click **Process selected**.
4. Wait until the status becomes **DONE** (or **FAILED**). Processing usually takes less than a minute, but if the file is large, it can take even longer.

### View Extracted Questions

1. Stay in the **Documents** tab and make sure the target document row is selected.
2. Go to the **Questions** tab.
3. Click **Load questions for selected document**.
4. Click any question row to preview its stem and choices in the preview box.
5. **If the `Review?` column shows `true`, the question may be inconsistent with the uploaded PDF.**  
   This indicates the model had low confidence or detected potential formatting/parsing issues.  
   Such questions should be manually reviewed to verify correctness before use.

### View Question Bank
1. Go to the **Bank** tab. 
2. There you will find all the processed questions sorted by their category
3. The `Confidence` and `Review?` tell you whether the selected question is accurate. 

---

# Limitations

1. **No diagram/image extraction inside questions (yet).**  
   Questions that rely on figures, graphs, or geometry diagrams may be incomplete in the extracted text.


2. **“FAILED” means the backend threw an error during processing.**  
   Earlier pages may still have saved questions, but the document status will be shown as **FAILED** overall.


3. **Camera-scanned or blurry PDFs reduce extraction accuracy.**  
   For best results, use clean, digitally generated PDFs. Blurry pages may produce low-confidence results or set `needsReview=true`.


4. **Answer/solution section auto-detection may stop extraction early.**  
   The processor stops when it detects keywords such as “答案”, “解析”, “参考答案”, “解析版”, "solution", or "answer". If these words appear before the actual answer section (e.g., in headers), extraction may stop prematurely.
