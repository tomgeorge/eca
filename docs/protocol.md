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

type ChatBehavior = 'agent' | 'ask';
```

_Response:_

```typescript
interface InitializeResult {
    
    /*
     * The models supported by the server.
     */
    models: ChatModel[];
    
    defaultModel: ChatModel;
    
    /*
     * The chat behavior.
     */
    chatBehavior: ChatBehavior;
    
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
     * The mode used by server to handle chat communication and actions.
     */
    mode?: 'agent' | 'ask';

    /**
     * Optional contexts about the current workspace.
     * Can include multiple different types of context.
     */
    contexts?: ChatContext[];
}

/**
 * The currently supported models, auto means let server decide.
 */
type ChatModel = 
    | 'o4-mini'
    | 'gpt-4.1'
    | 'claude-sonnet-4-0'
    | 'claude-opus-4-0'
    | 'claude-3-5-haiku-latest'
    | 'auto'
    OllamaRunningModel;
    
/**
 * Ollama running models available locally.
 */
type OllamaRunningModel = string

type ChatContext = FileContext | DirectoryContext | WebContext | CodeContext;

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
 * Context related to code snippets
 */
interface CodeContext {
    type: 'code';
    /**
     * The code content
     */
    content: string;
    /**
     * The programming language of the code
     */
    language: string;
    /**
     * Name or description of what this code represents
     */
    description?: string;
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

    /**
     * Optional metadata about the generation
     */
    metadata?: {
        /**
         * Number of tokens used in the generation
         */
        tokensUsed?: number;
    };
}

/**
 * Different types of content that can be received from the LLM
 */
type ChatContent = 
    | TextContent 
    | ProgressContent 
    | FileChangeContent;

/**
 * Simple text message from the LLM
 */
interface TextContent {
    type: 'text';
    /**
     * The text content
     */
    text: string;
    /**
     * Optional code blocks found in the text
     */
    codeBlocks?: [{
        /**
         * The code content
         */
        code: string;
        /**
         * The programming language of the code
         */
        language?: string;
    }];
}

/**
 * Details about the progress of the chat completion.
 */
interface ProgressContent {
    type: 'progress';

    /**
     * The statue of this progress
     */
    state: 'running' | 'finished';
    
    /**
     * The text detailing the progress
     */
    text?: string;
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
    chatId: string;

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
    chatId: string;

    /**
     * The returned available contexts.
     */
    contexts: ChatContext[];
}
```

### Completion (↩️)

Soon

### Edit (↩️)

Soon
