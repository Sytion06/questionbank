# Requirements

- Java 21
- Gradle (or use included Gradle Wrapper)
- OpenAI API Key

---

# Installation

## 1. Clone Repository

```bash
git clone https://github.com/yourusername/questionbank.git
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
http://localhost:8080
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