# ECA (Editor Code Assistant)

:warning: The project is still alpha and experimental, with bugs and missing features, but being consistently improved.

## Rationale 

An OSS editor agnostic tool that aims to bridge LLMs <-> Editors, giving the best UX possible with a custom protocol.

- **Editor-agnostic** protocol for any editor integrate.
- **Chat** interface: ask questions, review diffs, work together with an agent in your codebase.
- **AI-powered completions**, docs & refactorings.
- **Streaming responses** and structured error handling.
- **Pluggable models**: Ollama local models, OpenAI, Anthropic, more on the way.
- **Single configuration**: Configure eca behavior making it feel the same in any editor.

## Installation

Stable release:

```bash
sudo bash <(curl -s https://raw.githubusercontent.com/editor-code-assistant/eca/master/install)
```

nightly build:

```bash
sudo bash <(curl -s https://raw.githubusercontent.com/editor-code-assistant/eca/master/install) --version nightly
```

## Usage

Editors should spawn server via `eca server` and communicate via stdin/stdout.

## Protocol

The protocol can be found [here](./docs/protocol.md), it follows the same standard of LSP documentation, defining how server and client communicate with each other.

## Roadmap

- Centralize roadmap in a Github Project.
- Error handling
- Support more editors: VScode, IntelliJ and Vim.
- Improve docs
- Support more model options like thinking.
- Improve context passed to LLM, creating a repo map or translating to embedded vectors.
- Support more models.
- Support MCP configuration.
- Code completion using AI.
- Doc generation using AI.
- Edit/fix me support using AI.

## Contributing

Contributions are very welcome, please open a issue for discussion or pull request.
