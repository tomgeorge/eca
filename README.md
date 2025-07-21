<img src="images/logo.png" width="110" align="right">

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

# ECA (Editor Code Assistant)

:warning: The project is still alpha, expect bugs and WIP features, but being consistently improved and maintained, feedback is more than welcome.

_Demo using [eca-emacs](https://github.com/editor-code-assistant/eca-emacs)_
![demo](https://raw.githubusercontent.com/editor-code-assistant/eca-emacs/master/demo.gif)

_Demo using [eca-vscode](https://github.com/editor-code-assistant/eca-vscode)_
![demo](https://raw.githubusercontent.com/editor-code-assistant/eca-vscode/master/demo.gif)

<hr>
<p align="center">
  <a href="#installation"><strong>installation</strong></a> •
  <a href="./docs/configuration.md"><strong>configuration</strong></a> •
  <a href="./docs/capabilities.md"><strong>capabilities</strong></a> •
  <a href="./docs/protocol.md"><strong>protocol</strong></a>
</p>
<hr>

## Rationale 

<img src="images/rationale.jpg" width="500">

A Free and OpenSource editor-agnostic tool that aims to easily link LLMs <-> Editors, giving the best UX possible for AI pair programming using a well-defined protocol. The server is written in Clojure and heavily inspired by the [LSP protocol](https://microsoft.github.io/language-server-protocol/) which is a success case for this kind of integration.

- **Editor-agnostic** protocol for any editor to integrate.
- **Single configuration**: Configure eca making it work the same in any editor via global or local configs.
- **Chat** interface: ask questions, review code, work together to code.
- **Agentic** let LLM work as an agent with its native tools and MCPs you can configure.
- **Context** support: giving more details about your code to the LLM.
- **Multi models**: OpenAI, Anthropic, Ollama local models, and custom user config models.

## Installation

Eca is written in Clojure and compiled into a native binary via graalvm. You can download the binaries from Github Releases or use the install script for convenience:

Stable release:

```bash
bash <(curl -s https://raw.githubusercontent.com/editor-code-assistant/eca/master/install)
```

nightly build:

```bash
bash <(curl -s https://raw.githubusercontent.com/editor-code-assistant/eca/master/install) --version nightly
```

## Usage

Editors should spawn the server via `eca server` and communicate via stdin/stdout.

## Supported editors

- [Emacs](https://github.com/editor-code-assistant/eca-emacs)
- [VsCode](https://github.com/editor-code-assistant/eca-vscode)
- Intellij: Planned
- Vim: Planned, help welcome

## Protocol

The protocol can be found [here](./docs/protocol.md), it follows the same standard of LSP documentation, defining how server and client communicate with each other.

## Roadmap

Check the planned work [here](https://github.com/orgs/editor-code-assistant/projects/1/views/1).

## Troubleshooting

You can start eca with `--log-level debug` which should log helpful information in stderr buffer like what is being sent to LLMs.

## Contributing

Contributions are very welcome, please open an issue for discussion or a pull request.
For developer details, check [this doc](./docs/development.md).
