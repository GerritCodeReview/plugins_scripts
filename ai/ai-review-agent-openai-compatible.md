# Gerrit AI Review Agent for OpenAI Compatible provider

Implementation of the Gerrit's AI Code Review Agent API on top of an OpenAI Compatible provider.

[Install](#install-in-gerrit) this plugin and enable the Gerrit AI chat to enjoy
a side-by-side collaboration with OpenAI Compatible provider on the Change screen.

This plugin has been validated against [Open WebUI](https://openwebui.com/).

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

Gerrit v3.14 or later with the following additional plugins:

- [Groovy scripting provider](https://gerrit.googlesource.com/plugins/scripting/groovy-provider/)
- [GerritForge's AI Review Agent Provider](https://github.com/GerritForge/ai-review-agent-provider)

### [Install in Gerrit](#install-in-gerrit)

Copy the `ai-review-agent-openai-compatible-1.0.groovy` script into your Gerrit site (`$GERRIT_SITE`)
plugins' directory.

```bash
cp ai-review-agent-openai-compatible-1.0.groovy "$GERRIT_SITE/plugins/"
```

### Configuration

Configure the OpenAI Compatible provider API URL in the `gerrit.config` like this:

```ini
[plugin "ai-review-agent-openai-compatible"]
   baseUrl = http://openai-compatible-server:3000/api/v1
```
