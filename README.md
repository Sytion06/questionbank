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

1. Start backend.
2. Start desktop client.
3. Upload a PDF.
4. Trigger processing.
5. View extracted questions.

# Limitations

1. The system currently does **not extract images embedded within questions**. If a question contains diagrams, figures, or graphs, they will not be included in the extracted output.

2. If a document status shows **FAILED**, it is usually because one or two questions could not be parsed correctly by the model. In most cases, the majority of questions are still successfully extracted.

3. To reduce extraction failures, avoid uploading **camera-scanned exam papers**. For best results, upload clear, digitally generated PDF files instead.
