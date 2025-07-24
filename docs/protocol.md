# ECA Protocol

The ECA (Editor Code Assistant) protocol is JSON-RPC 2.0-based protocol heavily insipired by the [LSP (Language Server Protocol)](https://microsoft.github.io/language-server-protocol/), that enables communication between multiple code editors/IDEs and ECA process (server), which will interact with multiple LLMs. It follows similar patterns to the LSP but is specifically designed for AI code assistance features.

Key characteristics:
- Provides a protocol standard so different editors can use the same language to offer AI features.
- Supports bidirectional communication (client to server and server to client)
- Handles both synchronous requests and asynchronous notifications
- Includes built-in support for streaming responses
- Provides structured error handling

## Base Protocol

The base protocol consists of a header and a content part (comparable to HTTP). The header and content part are
separated by a `\r\n`.

### Header Part

The header part consists of header fields. Each header field is comprised of a name and a value, separated by `: ` (a colon and a space). The structure of header fields conforms to the [HTTP semantic](https://tools.ietf.org/html/rfc7230#section-3.2). Each header field is terminated by `\r\n`. Considering the last header field and the overall header itself are each terminated with `\r\n`, and that at least one header is mandatory, this means that two `\r\n` sequences always immediately precede the content part of a message.

Currently the following header fields are supported:

| Header Field Name | Value Type  | Description |
|:------------------|:------------|:------------|
| Content-Length    | number      | The length of the content part in bytes. This header is required. |
| Content-Type      | string      | The mime type of the content part. Defaults to application/vscode-jsonrpc; charset=utf-8 |
{: .table .table-bordered .table-responsive}

The header part is encoded using the 'ascii' encoding. This includes the `\r\n` separating the header and content part.

### Content Part

Contains the actual content of the message. The content part of a message uses [JSON-RPC 2.0](https://www.jsonrpc.org/specification) to describe requests, responses and notifications. The content part is encoded using the charset provided in the Content-Type field. It defaults to `utf-8`, which is the only encoding supported right now. If a server or client receives a header with a different encoding than `utf-8` it should respond with an error.

### Example:

```
Content-Length: ...\r\n
\r\n
{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        ...
    }
}
```

## Lifecycle Messages

The protocol defines a set of lifecycle messages that manage the connection and state between the client (editor) and server (code assistant):

### Initialize (↩️)

The first request sent from client to server. This message:
- Establishes the connection
- Allows the server to index the project
- Enables capability negotiation
- Sets up the workspace context

_Request:_

* method: `initialize`
* params: `InitializeParams` defined as follows:

```typescript
interface InitializeParams {
    /**
     * The process Id of the parent process that started the server. Is null if
     * the process has not been started by another process. If the parent
     * process is not alive then the server should exit (see exit notification)
     * its process.
     */
     processId: integer | null;
     
     /**
     * Information about the client
     */
    clientInfo?: {
        /**
         * The name of the client as defined by the client.
         */
        name: string;

        /**
         * The client's version as defined by the client.
         */
        version?: string;
    };
    
    /**
     * User provided initialization options.
     */
    initializationOptions?: {
        /*
         * The chat behavior.
         */
         chatBehavior?: ChatBehavior;
    };
    
    /**
     * The capabilities provided by the client (editor or tool)
     */
    capabilities: ClientCapabilities;
    
    /**
     * The workspace folders configured in the client when the server starts.
     * If client doesn´t support multiple projects, it should send a single 
     * workspaceFolder with the project root.
     */
    workspaceFolders: WorkspaceFolder[];
}

interface WorkspaceFolder {
    /**
     * The associated URI for this workspace folder.
     */
    uri: string;

    /**
     * The name of the workspace folder. Used to refer to this folder in the user interface.
     */
    name: string;
}

interface ClientCapabilities {
    codeAssistant?: {
        chat?: boolean;
        doc?: boolean;
        edit?: boolean;
        fix?: boolean;
    }
}

type ChatBehavior = 'agent' | 'chat';
```

_Response:_

```typescript
interface InitializeResponse {
    
    /*
     * The models supported by the server.
     */
    models: ChatModel[];
    
    /*
     * Default model used by server.
     */
    chatDefaultModel: ChatModel;
    
    /*
     * The chat behaviors available.
     */
    chatBehaviors: ChatBehavior[];
    
    /*
     * Default chat behavior used by server.
     */
    chatDefaultBehavior: ChatBehavior;
    
    /*
     * The chat welcome message when chat is cleared or in a new state.
     */
    chatWelcomeMessage: string;
}
```

### Initialized (➡️)

A notification sent from the client to the server after receiving the initialize response. This message:
- Confirms that the client is ready to receive requests
- Signals that the server can start sending notifications
- Indicates that the workspace is fully loaded

_Notification:_

* method: `initialized`
* params: `InitializedParams` defined as follows:

```typescript
interface InitializedParams {}
```

### Shutdown (↩️)

A request sent from the client to the server to gracefully shut down the connection. This message:
- Allows the server to clean up resources
- Ensures all pending operations are completed
- Prepares for a clean disconnection

_Request:_

* method: `shutdown`
* params: none

_Response:_

* result: null
* error: code and message set in case an exception happens during shutdown request.

### Exit (➡️)

A notification sent from the client to the server to terminate the connection. This message:
- Should be sent after a shutdown request
- Signals the server to exit its process
- Ensures all resources are released

_Notification:_

* method: `exit`
* params: none 

## Code Assistant Features

### Chat Prompt (↩️)

A request sent from client to server, starting or continuing a chat in natural language as an agent.
Used for broader questions or continuous discussion of project/files.

_Request:_ 

* method: `chat/prompt`
* params: `ChatPromptParams` defined as follows:

```typescript
interface ChatPromptParams {
    /**
     * The chat session identifier. If not provided, a new chat session will be created.
     */
    chatId?: string;
    
    /**
     * This message unique identifier used to match with next async messages.
     */
    requestId: string;

    /**
     * The message from the user in native language
     */
    message: string;

    /**
     * Specifies the AI model to be used for chat responses.
     * Different models may have different capabilities, response styles,
     * and performance characteristics.
     */
    model?: ChatModel;

    /**
     * The chat behavior used by server to handle chat communication and actions.
     */
    behavior?: ChatBehavior;

    /**
     * Optional contexts about the current workspace.
     * Can include multiple different types of context.
     */
    contexts?: ChatContext[];
}

/**
 * The currently supported models.
 */
type ChatModel = 
    | 'o4-mini'
    | 'o3'
    | 'gpt-4.1'
    | 'claude-sonnet-4-0'
    | 'claude-opus-4-0'
    | 'claude-3-5-haiku-latest'
    OllamaRunningModel;
    
/**
 * Ollama running models available locally.
 */
type OllamaRunningModel = string

type ChatContext = FileContext | DirectoryContext | WebContext | RepoMapContext;

/**
 * Context related to a file in the workspace
 */
interface FileContext {
    type: 'file';
    /**
     * Path to the file
     */
    path: string;
}

/**
 * Context related to a directory in the workspace
 */
interface DirectoryContext {
    type: 'directory';
    /**
     * Path to the directory
     */
    path: string;
}

/**
 * Context related to web content
 */
interface WebContext {
    type: 'web';
    /**
     * URL of the web content
     */
    url: string;
}

/**
 * Context about the workspaces repo-map, automatically calculated by server.
 * Clients should include this to chat by default but users may want exclude 
 * this context to reduce context size if needed.
 */
interface RepoMapContext {
    type: 'repoMap'; 
 }
```

_Response:_

```typescript
interface ChatPromptResponse {
    /**
     * Unique identifier for this chat session
     */
    chatId: string;
    
    /*
     * The model used for this chat request.
     */
    model: ChatModel;
    
    status: 'success';
}
```

### Chat Content Received (⬅️)

A server notification with a new content from the LLM.

_Notification:_ 

* method: `chat/contentReceived`
* params: `ChatContentReceivedParams` defined as follows:

```typescript
interface ChatContentReceivedParams {
    /**
     * The chat session identifier this content belongs to
     */
    chatId: string;

    /**
     * The content received from the LLM
     */
    content: ChatContent;
    
    /**
     * The owner of this content.
     */
    role: 'user' | 'system' | 'assistant';
}

/**
 * Different types of content that can be received from the LLM
 */
type ChatContent = 
    | TextContent 
    | URLContent 
    | ProgressContent 
    | UsageContent
    | FileChangeContent
    | ToolCallPrepareContent
    | ToolCallRunContent
    | ToolCalledContent;

/**
 * Simple text message from the LLM
 */
interface TextContent {
    type: 'text';
    /**
     * The text content
     */
    text: string;
}

/**
 * URL content message from the LLM
 */
interface URLContent {
    type: 'url';

    /**
     * The URL title
     */
    title: string;

    /**
     * The URL link
     */
    url: string;
}

/**
 * Details about the chat's usage, like used tokens and costs.
 */
interface UsageContent {
    type: 'usage';
    
    /*
     * Number of tokens sent on previous prompt including all context used by ECA.
     */
    messageInputTokens: number;
    
    /*
     * Number of tokens received from LLm in last prompt.
     */
    messageOutputTokens: number;
    
    /**
     * The total input + output tokens of the whole chat session so far.
     */
    sessionTokens: number;
    
    /**
     * The cost of the last sent message summing input + output tokens.
     */
    messageCost?: string; 
    
    /**
     * The cost of the whole chat session so far.
     */
    sessionCost?: string;
}

/**
 * File changes that may require user approval
 */
interface FileChangeContent {
    type: 'fileChange';
    
    /**
     * Description of what changes will be made
     */
    description: string;

    /**
     * Whether this change requires manual approval from the user
     */
    manualApproval: boolean;

    /**
     * The file to be changed
     */
    file?: string;

    /**
     * The changes to be applied
     */
    changes?: [{
        /**
         * The type of change
         */
        kind: 'create' | 'change' | 'delete';
        
        /**
         * The content to be added/modified
         */
        content?: string;
        
        /**
         * The range of lines to be modified/deleted
         */
        range?: {
            /**
             * The starting line number (1-based)
             */
            startLine: number;
            /**
             * The ending line number (1-based)
             */
            endLine: number;
        };
    }];
}

/**
 * Tool call that LLM is preparing to execute.
 */
interface ToolCallPrepareContent {
    type: 'toolCallPrepare';

    origin: ToolCallOrigin;
    
    /**
     * id of the tool call
     */
    id: string;
    
    /**
     * Name of the tool
     */
    name: string;
    
    /*
     * Argument text of this tool call
     */
    argumentsText: string;
    
    /**
     * Whether this call requires manual approval from the user.
     */
    manualApproval: boolean;
}

/**
 * Tool call final request that LLM may trigger.
 */
interface ToolCallRunContent {
    type: 'toolCallRun';
    
    origin: ToolCallOrigin;
    
    /**
     * id of the tool call
     */
    id: string;
    
    /**
     * Name of the tool
     */
    name: string;
    
    /*
     * Arguments of this tool call
     */
    arguments: {[key: string]: string};
    
    /**
     * Whether this call requires manual approval from the user.
     */
    manualApproval: boolean;
}

/**
 * Tool call result that LLM trigerred and was executed already.
 */
interface ToolCalledContent {
    type: 'toolCalled';
    
    origin: ToolCallOrigin;
    
    /**
     * id of the tool call
     */
    id: string;
    
    /**
     * Name of the tool
     */
    name: string;
    
    /*
     * Arguments of this tool call
     */
    arguments: string[];
    
    /**
     * the result of the tool call.
     */
    outputs: [{
        /*
         * The type of this output
         */
        type: 'text';
       
        /**
         * The content of this output
         */
        content: string;
        
        /**
         * Whether it was a error
         */
        error: boolean;
    }];
}

interface ToolCallRejected {
    type: 'toolCallRejected';
    
    origin: ToolCallOrigin;
    
    /**
     * id of the tool call
     */
    id: string;
    
    /**
     * Name of the tool
     */
    name: string;
    
    /*
     * Arguments of this tool call
     */
    arguments: {[key: string]: string};
    
    /**
     * The reason why this tool call was rejected
     */
    reason: 'user';
}

type ToolCallOrigin = 'mcp' | 'native';
```

### Chat Query Context (↩️)

A request sent from client to server, querying for the available context to user add to prompt calls.

_Request:_ 

* method: `chat/queryContext`
* params: `ChatQueryParams` defined as follows:

```typescript
interface ChatQueryContextParams {
    /**
     * The chat session identifier.
     */
    chatId?: string;

    /**
     * The query to filter results, blank string returns all available contexts.
     */
    query: string;
    
    /**
     * The already considered contexts.
     */
    contexts: ChatContext[];
}
```

_Response:_

```typescript
interface ChatQueryContextResponse {
    /**
     * The chat session identifier.
     */
    chatId?: string;

    /**
     * The returned available contexts.
     */
    contexts: ChatContext[];
}
```

### Chat approve tool call (➡️)

A client notification for server to approve a waiting tool call.
This will execute the tool call and continue the LLM chat loop.

_Notification:_

* method: `chat/toolCallApprove`
* params: `ChatToolCallApproveParams` defined as follows:

```typescript
interface ChatToolCallApproveParams {
    /**
     * The chat session identifier.
     */
    chatId: string;
    
    /**
     * The tool call identifier to approve.
     */
    toolCallId: string; 
}
```

### Chat reject tool call (➡️)

A client notification for server to reject a waiting tool call.
This will not execute the tool call and return to the LLM chat loop.

_Notification:_

* method: `chat/toolCallReject`
* params: `ChatToolCallRejectParams` defined as follows:

```typescript
interface ChatToolCallRejectParams {
    /**
     * The chat session identifier.
     */
    chatId: string;
    
    /**
     * The tool call identifier to reject.
     */
    toolCallId: string; 
}
```

### Chat stop prompt (➡️)

A client notification for server to stop the current chat prompt with LLM if running.
This will stop LLM loops or ignore subsequent LLM responses so other prompts can be trigerred.

_Notification:_

* method: `chat/promptStop`
* params: `ChatPromptStopParams` defined as follows:

```typescript
interface ChatPromptStopParams {
    /**
     * The chat session identifier.
     */
    chatId: string;
}
```

### Chat delete (↩️)

A client request to delete a existing chat, removing all previous messages and used tokens/costs from memory, good for reduce context or start a new clean chat.
After response, clients should reset chat UI to a clean state.

_Request:_ 

* method: `chat/delete`
* params: `ChatDeleteParams` defined as follows:

```typescript
interface ChatDeleteParams {
    /**
     * The chat session identifier.
     */
    chatId?: string;
}
```

_Response:_

```typescript
interface ChatDeleteResponse {}
```

### Completion (↩️)

Soon

### Edit (↩️)

Soon

## Configuration

### Tool updated (⬅️)

A server notification about a tool status update like a MCP or native tool.
This is useful for clients present to user the list of configured tools/MCPs,
their status and available tools and actions.

_Request:_ 

* method: `tool/serverUpdated`
* params: `ToolServerUpdatedParams` defined as follows:

_Response:_


```typescript
type ToolServerUpdatedParams = EcaServerUpdatedParams | MCPServerUpdatedParams;

interface EcaServerUpdatedParams {
    type: 'native';
    
    name: 'ECA';
    
    status: 'running';

    /**
     * The built-in tools supported by eca.
     */
    tools: ServerTool[];
}

interface MCPServerUpdatedParams {
    type: 'mcp';
    
    /**
     * The server name.
     */
    name: string;
    
    /**
     * The command to start this server.
     */
    command: string;

    /**
     * The arguments to start this server.
     */
    args: string[];
    
    /**
     * The status of the server.
     */
    status: 'running' | 'starting' | 'stopped' | 'failed' | 'disabled';
    
    /**
     * The tools supported by this mcp server if not disabled.
     */
    tools?: ServerTool[];
}

interface ServerTool {
    /**
     * The server tool name.
     */
    name: string;
    
    /**
     * The server tool description.
     */
    description: string;
    
    /**
     * The server tool parameters.
     */
    parameters: any; 
}
```

### Add MCP (↩️)

Soon

## General features

### showMessage (⬅️)

A notification from server telling client to present a message to user.

_Request:_ 

* method: `$/showMessage`
* params: `ShowMessageParams` defined as follows:

```typescript
interface ShowMessageParams {
    /**
     * The message type. See {@link MessageType}.
    */
    type: MessageType;

    /**
     * The actual message.
     */
    message: string;
}

export type MessageType = 'error' | 'warning' | 'info';
```
