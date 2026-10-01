# snake-apk

Материалы к [docs/snake-apk-agents.md](../docs/snake-apk-agents.md) — облачные модели
автономно собирают Android-змейку.

| Папка | Что внутри |
|---|---|
| `harness/` | агентный цикл: OpenAI- и Anthropic-формат |
| `projects/<модель>/` | исходники, которые написала модель (без build-папок) |
| `logs/` | `*.log` — ход работы, `*.jsonl` — полная переписка с моделью |
| `results/` | итог по каждой модели: ходы, время, токены, сборки |
| `apk/` | готовые APK |
| `claude-code-sandbox/` | лаунчер Claude Code через прокси + защитный хук |

Запуск агента:

```bash
python harness/agent_openai.py <имя> <модель> <ключ>
# для моделей с долгими ответами (GLM):
STREAM=1 MAX_TOKENS=32000 python harness/agent_openai.py glm glm-5.3 <ключ>
```

Пути к Gradle, JDK и Android SDK прописаны в начале скриптов — поменяйте под себя.
