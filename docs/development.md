# ECA Development

## Coding 

There are several ways of finding and fixing a bug or implementing a new feature:

- Create a test for your bug/feature, then implement the code following the test (TDD).
- Build a local `eca` JVM embedded binary using `bb debug-cli` (requires `babashka`), and test it manually in your client pointing to it. After started, you can connect to the nrepl port mentioned in eca stderr buffer, do you changes, evaluate and it will be affected on the running eca.
  - Using a debug binary you can check eca's stderr buffer and look for a nrepl port, and connect to the REPL, make changes to the running eca process (really handy).

## Supporting a new editor

When supporting a new editor, it's important to keep UX consistency across editors, check how other editors done or ask for help.

This step-by-step feature implementation help track progress and next steps:

```markdown
- [ ] Create the plugin/extension repo (editor-code-assistant/eca-<editor> would be ideal), ask maintainers for permission.
- Server
  - Manage ECA server process.
    - [ ] Automatic download of latest server.
    - [ ] Allow user specify server path/args.
    - [ ] Commands for Start/stop server from editor.
    - [ ] Show server status (modeline, bottom of editor, etc).
  - [ ] JSONRPC communication with eca server process via stdin/stdout sending/receiving requests and notifications, check [protocol](./protocol.md).
  - [ ] Allow check eca server process stderr for debugging/logs.
  - [ ] Support `initialize` and `initialized` methods.
  - [ ] Support `exit` and `shutdown` methods.
- Chat
  - [ ] Oepn chat window
  - [ ] Send user messages via `chat/prompt` request.
  - [ ] Clear chat and Reset chat.
  - [ ] Support receive chat contents via `chat/contentReceived` notification.
  - [ ] Present and allow user change behaviors and models returned from `initialize` request.
  - [ ] Present and add contexts via `chat/queryContext` request
  - [ ] Support tools contents: run/approval/reject via `chat/toolCallApprove` or `chat/toolCallReject`.
  - [ ] Support reason/thoughts content blocks.
  - [ ] Show MCPs summary (running, failed, pending).
  - [ ] Support chat commands (`/`) auto completion, querying via `chat/queryCommands`.
  - [ ] Show usage (costs/tokens) from usage content blocks.
  - [ ] keybindings: navigate through chat blocks/messages, clear chat.
- MCP
  - [ ] Open MCP details window
  - [ ] Receive MCP server updates and update chat and mcp-details ux.
- [ ] Basic plugin/extension documentation
```

Create a issue to help track the effort copying and pasting these check box to help track progress.
