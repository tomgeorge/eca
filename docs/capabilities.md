# Capabilities

## Built-in tools

ECA support built-in tools to avoid user extra installation and configuration, these tools are always included on models requests that support tools and can be disabled/configured via config `built-in-tools`.

### Filesystem

Provides access to filesystem under workspace root, listing and reading files and directories.

## Supported LLM models and capaibilities

| model     | MCP / tools | thinking/reasioning |
|-----------|-------------|---------------------|
| OpenAI    | √           | X                   |
| Anthropic | √           | X                   |
| Ollama    | X           | X                   |

### OpenAI

- [o4-mini](https://platform.openai.com/docs/models/o4-mini)
- [o3](https://platform.openai.com/docs/models/o3)
- [gpt-4.1](https://platform.openai.com/docs/models/gpt-4.1)

### Anthropic

- [claude-sonnet-4-0](https://docs.anthropic.com/en/docs/about-claude/models/overview)
- [claude-opus-4-0](https://docs.anthropic.com/en/docs/about-claude/models/overview)
- [claude-3-5-haiku-latest](https://docs.anthropic.com/en/docs/about-claude/models/overview)

### Ollama

- [any local ollama model](https://ollama.com/search)

