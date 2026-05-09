# Gerrit AI Review Agent for Claude

Implementation of the Gerrit's AI Code Review Agent API on top of Anthropic's Claude.

[Install](#install-in-gerrit) this plugin and enable the Gerrit AI chat to enjoy
a side-by-side collaboration with Claude on the Change screen.

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

Gerrit v3.14 with the following additional plugins:

- [Groovy scripting provider](https://gerrit.googlesource.com/plugins/scripting/groovy-provider/)
- [GerritForge's AI Review Agent Provider](https://github.com/GerritForge/ai-review-agent-provider)

### [Install in Gerrit](#install-in-gerrit)

Copy the `ai-review-agent-claude-1.0.groovy` script into your Gerrit site (`$GERRIT_SITE`)
plugins' directory.

```bash
cp ai-review-agent-claude-1.0.groovy "$GERRIT_SITE/plugins/"
```
