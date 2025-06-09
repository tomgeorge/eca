# ECA protocol

The ECA (Editor Code Assistant) protocol is JSON-RPC 2.0 based protocol that enables communication between multiple code editors/IDEs and ECA process (server), which will interact with multiple LLMs. It follows similar patterns to the Language Server Protocol (LSP) but is specifically designed for AI code assistance features.

Key characteristics:
- Provides a protocol standard so different editors can use the same language to offer AI features.
- Supports bidirectional communication (client to server and server to client)
- Handles both synchronous requests and asynchronous notifications
- Includes built-in support for streaming responses
- Provides structured error handling

## Lifecycle Messages

The protocol defines a set of lifecycle messages that manage the connection and state between the client (editor) and server (code assistant):

### Initialize (:leftwards_arrow_with_hook:)

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
    initializationOptions?: Any;
    
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
```

`ClientCapabilities`
```typescript
interface ClientCapabilities {
    codeAssistant?: {
        chat?: boolean;
        doc?: boolean;
        edit?: boolean;
        fix?: boolean;
    }
}
```

_Response:_

```typescript
interface InitializedResult {}
```

### Initialized (:arrow_right:)

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

### Shutdown (:leftwards_arrow_with_hook:)

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

### Exit (:arrow_right:)

A notification sent from the client to the server to terminate the connection. This message:
- Should be sent after a shutdown request
- Signals the server to exit its process
- Ensures all resources are released

_Notification:_

* method: `exit`
* params: none

## Code Assistant Features

### Chat Prompt (:leftwards_arrow_with_hook:)

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
     * Optional context about the current workspace state.
     * Can include multiple different types of context.
     */
    context?: ChatContext[];
}

type ChatModel = 
    | 'gpt-4'
    | 'gpt-3.5-turbo'
    | 'claude-2'
    | "codellama-34b"
    | "auto";

type ChatContext = FileContext | WebContext | TerminalContext | CodeContext;

/**
 * Context related to a file in the workspace
 */
interface FileContext {
    type: 'file';
    /**
     * Path to the file
     */
    path: string;
    /**
     * Selected lines in the file, if any
     */
    selection?: {
        startLine: number;
        endLine: number;
    };
    /**
     * Current cursor position, if any
     */
    cursor?: {
        line: number;
        character: number;
    };
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

    status: 'success' | 'connection-error';

    /**
     * Message with details about the sent chat prompt.
     */ 
    resultMessage: string;
}
```

### Chat Content Received (:arrow_left:)

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
     * Whether this is the final content or more is coming
     */
    isComplete: boolean;

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

### Completion (:leftwards_arrow_with_hook:)

### Documentation (:leftwards_arrow_with_hook:)
