# Changelog

## Unreleased

- Add native tools: filesystem
- Add MCP/tool support for ollama models.
- Improve ollama integration only requiring `ollama serve` to be running.
- Improve chat history and context passed to all LLM providers.
- Add support for prompt caching for Anthropic models.

## 0.1.0

- Allow comments on `json` configs.
- Improve MCP tool call feedback.
- Add support for env vars in mcp configs.
- Add `mcp/serverUpdated` server notification.

## 0.0.4

- Add env support for MCPs
- Add web_search capability
- Add `o3` model support.
- Support custom API urls for OpenAI and Anthropic
- Add `--log-level <level>` option for better debugging.
- Add support for global config file.
- Improve MCP response handling.
- Improve LLM streaming response handler.

## 0.0.3

- Fix ollama servers discovery
- Fix `.eca/config.json` read from workspace root
- Add support for MCP servers

## 0.0.2

- First alpha release

## 0.0.1
