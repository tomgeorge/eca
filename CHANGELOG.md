# Changelog

## Unreleased

## 0.9.0

- Include eca as a  server with tools.
- Support disable tools via config.
- Improve ECA prompt to be more precise and output with better quality

## 0.8.1

- Make generic tool server updates for eca native tools.

## 0.8.0

- Support tool call approval and configuration to manual approval.
- Initial support for repo-map context.

## 0.7.0

- Add client request to delete a chat.

## 0.6.1

- Support defaultModel in custom providers.

## 0.6.0

- Add usage tokens + cost to chat messages.

## 0.5.1

- Fix openai key

## 0.5.0

- Support custom LLM providers via config.

## 0.4.3

- Improve context query performance.

## 0.4.2

- Fix output of errored tool calls.

## 0.4.1

- Fix arguments test when preparing tool call.

## 0.4.0

- Add support for global rules.
- Fix origin field of tool calls.
- Allow chat communication with no workspace opened.

## 0.3.1

- Improve default model logic to check for configs and env vars of known models.
- Fix past messages sent to LLMs.

## 0.3.0

- Support stop chat prompts via `chat/promptStop` notification.
- Fix anthropic messages history.

## 0.2.0

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
