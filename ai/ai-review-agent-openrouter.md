# Gerrit AI Review Agent for OpenRouter

Implementation of the Gerrit's AI Code Review Agent API on top of
[OpenRouter](https://openrouter.ai/), a unified gateway exposing 300+ LLMs
(Anthropic, OpenAI, Google, Meta, DeepSeek, xAI and more) behind a single
OpenAI-compatible API.

[Install](#install-in-gerrit) this plugin and enable the Gerrit AI chat to enjoy
a side-by-side collaboration with the LLM of your choice on the Change screen.

## License

This script is licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## How to use

### Prerequisites

Gerrit v3.14 or newer with the following additional plugins:

- [Groovy scripting provider](https://github.com/GerritForge/groovy-provider/)
- [GerritForge's AI Review Agent Provider](https://github.com/GerritForge/ai-review-agent-provider)

An OpenRouter API key: create one at https://openrouter.ai/keys. Free-tier
models (suffix `:free`) work with any key but are rate-limited; paid models
require credits on the account.

### Available models

The dropdown is assembled at runtime from a small static list combined with
a live query against the OpenRouter catalog (`/api/v1/models`). The catalog
result is cached for 24 hours, and re-fetched immediately if a review call
returns HTTP 404 (i.e. a previously listed slug went away upstream).

**Static — paid floating aliases** (always-fresh, no script update needed):

- `~anthropic/claude-opus-latest` — auto-tracks newest Opus
- `~anthropic/claude-sonnet-latest` — auto-tracks newest Sonnet
- `~openai/gpt-latest` — auto-tracks newest GPT
- `~google/gemini-pro-latest` — auto-tracks newest Gemini Pro
- `~anthropic/claude-haiku-latest` — cheaper/faster Anthropic
- `~google/gemini-flash-latest` — cheaper/faster Google
- `~openai/gpt-mini-latest` — cheaper/faster OpenAI

**Dynamic — DeepSeek** (latest `*-pro` slug): the highest-versioned
`deepseek/deepseek-v<N>-pro` slug from the live catalog. OpenRouter does
not mint a floating alias for DeepSeek, so we approximate by parsing the
version number from the id and picking the largest. Currently that
resolves to `deepseek/deepseek-v4-pro` (1M context, top open-vendor
SWE-bench Verified).

**Dynamic — free tier** (top 5 by code-review heuristic, picked live
from the catalog): typically GLM, GPT-OSS, Qwen3-coder variants
depending on what OpenRouter currently exposes. Free-tier slugs rotate
frequently; dynamic selection avoids stale hard-coded lists. Qwen
entries are often rate-limited (HTTP 429) under load — the script
retries once and then surfaces a user-facing notice in the chat panel.

The heuristic combines four signals available in the catalog payload:

| Signal | Weight | Why |
|---|---|---|
| id contains `coder` / `code` | +100 | direct evidence of a code-specialized model |
| reasoning mode (`supported_parameters` includes `reasoning`, or id contains `thinking`) | +60 | reasoning models generally outperform plain chat on code review |
| big-vendor namespace (`openai/`, `anthropic/`, `google/`, `meta-llama/`, `deepseek/`, `qwen/`, `z-ai/`, `mistralai/`, `nvidia/`) | +40 | empirically these slugs rotate less and respond more reliably |
| `context_length / 10000` | tie-breaker | more diff fits in prompt |

The scoring is intentionally cheap to evaluate (no extra network calls)
and uses only fields present in the catalog response. It is a
heuristic, not a benchmark; if a vendor publishes a low-quality
"code-mini" free model it would still rank highly. Re-tune the weights
in the script if real-world picks regress.

**Ordering**: the dropdown serves the paid options first (always-fresh
`~latest` aliases, then the dynamically-selected DeepSeek flagship),
followed by the five dynamic free picks. That matches the strongest
quality guarantees at the top of the chat panel selector.

If the catalog fetch fails (network outage, etc.) the dropdown falls
back to the seven static `~latest` aliases above so reviews are not
blocked.

Grok-4 is deliberately not surfaced: it ranks mid-pack on code-review
benchmarks (SWE-bench / Aider polyglot) compared to the Anthropic,
OpenAI, Google and DeepSeek frontier. It remains reachable through the
OpenRouter catalog for users who explicitly want it.

### [Install in Gerrit](#install-in-gerrit)

Copy the `ai-review-agent-openrouter-1.0.groovy` script into your Gerrit site (`$GERRIT_SITE`)
plugins' directory.

```bash
cp ai-review-agent-openrouter-1.0.groovy "$GERRIT_SITE/plugins/"
```
