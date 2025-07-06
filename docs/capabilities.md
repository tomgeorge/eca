# Capabilities

## Native tools

ECA support built-in tools to avoid user extra installation and configuration, these tools are always included on models requests that support tools and can be [disabled/configured via config](./configuration.md) `nativeTools`.

Some native tools like `filesystem` have MCP alternatives, but ECA having them built-in avoid the need to external dependencies like npx.

### Filesystem

Provides access to filesystem under workspace root, listing and reading files and directories a subset of [official MCP filesystem](https://mcpserverhub.com/servers/filesystem), important for agentic operations, without the need to support NPM or other tools.

- `read_file`: read a file content.
- `write_file`: write content to file.
- `move_file`: move/rename a file.
- `list_directory`: list a directory.
- `search_files`: search in a path for files matching a pattern.
- `grep`: ripgrep/grep for paths with specified content.
- `replace_in_file`: replace a text with another one in file.

### TODO - Shell

### TODO - Web

## Supported LLM models and capaibilities

| model     | MCP / tools | thinking/reasioning | prompt caching |
|-----------|-------------|---------------------|----------------|
| OpenAI    | √           | X                   | X              |
| Anthropic | √           | X                   | √              |
| Ollama    | √           | X                   | X              |

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

