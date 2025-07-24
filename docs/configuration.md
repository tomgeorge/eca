# Configuration

## Ways to configure

Check all available configs [here](../src/eca/config.clj#L17).
There are 3 ways to configure ECA following this order of priority:

### InitializationOptions (convenient for editors)

Client editors can pass custom settings when sending the `initialize` request via the `initializationOptions` object:

```javascript
"initializationOptions": {
  "chatBehavior": "chat"
}
```

### Local Config file (convenient for users)

`.eca/config.json`
```javascript
{
  "chatBehavior": "chat"
}
```

### Global config file (convenient for users and multiple projects)

`~/.config/eca/config.json`
```javascript
{
  "chatBehavior": "chat"
}
```

### Env Var

Via env var during server process spawn:

```bash
ECA_CONFIG='{"myConfig": "my_value"}' eca server
```

## Rules

Rules are contexts that are passed to the LLM during a prompt and are useful to tune prompts or LLM behavior.
Rules are Multi-Document context files (`.mdc`) and the following metadata is supported:

- `description`: a description used by LLM to decide whether to include this rule in context, absent means always include this rule.
- `globs`: list of globs separated by `,`. When present the rule will be applied only when files mentioned matches those globs.

There are 3 possible ways to configure rules following this order of priority:

### Project file

A `.eca/rules` folder from the workspace root containing `.mdc` files with the rules.

`.eca/rules/talk_funny.mdc`
```markdown
--- 
description: Use when responding anything
---

- Talk funny like Mickey!
```

### Global file

A `$XDG_CONFIG_HOME/eca/rules` or `~/.config/eca/rules` folder containing `.mdc` files with the rules.

`~/.config/eca/rules/talk_funny.mdc`
```markdown
--- 
description: Use when responding anything
---

- Talk funny like Mickey!
```

### Config

Just add to your config the `:rules` pointing to `.mdc` files that will be searched from the workspace root if not an absolute path:

```javascript
{
  "rules": [{"path": "my-rule.mdc"}]
}
```

## MCP

For MCP servers configuration, use the `mcpServers` config, example:

`.eca/config.json`
```javascript
{
  "mcpServers": {
    "memory": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-memory"]
    }
  }
}
```

## Custom LLM providers

It's possible to configure ECA to be aware of custom LLM providers if they follow a API schema similar to currently supported ones (openai, anthropic), example for a custom hosted litellm server:

```javascript
{
  "customProviders": {
    "my-company": {
       "api": "openai",
       "urlEnv": "MY_COMPANY_API_URL", // or "url": "https://litellm.my-company.com",
       "keyEnv": "MY_COMPANY_API_KEY", // or "key": "123",
       "models": ["gpt-4.1", "deepseek-r1"],
       "defaultModel": "deepseek-r1"
    }
  }
}
```

## All configs

### Schema

```typescript
interface Config {
    openaiApiKey?: string;
    anthropicApiKey?: string;
    rules: [{path: string;}];
    nativeTools: {
        filesystem: {enabled: boolean};
         shell: {enabled: boolean;
                 excludeCommands: string[]};
    };
    disabledTools: string[],
    toolCall?: {
      manualApproval?: boolean,
    };
    mcpTimeoutSeconds: number;
    mcpServers: {[key: string]: {
        command: string;
        args?: string[];
        disabled?: boolean; 
    }};
    customProviders: {[key: string]: {
        api: 'openai' | 'anthropic';
        models: string[];
        defaultModel?: string;
        url?: string;
        urlEnv?: string;
        key?: string;
        keyEnv?: string;
    }};
    ollama?: {
        host: string;
        port: string;
        useTools: boolean;
    };
    chat?: {
        welcomeMessage: string;
    };
    index?: {
        ignoreFiles: [{
            type: string;
        }];
    };
}
```

### Default values

```javascript
{
  "openaiApiKey" : null,
  "anthropicApiKey" : null,
  "rules" : [],
  "nativeTools": {"filesystem": {"enabled": true},
                  "shell": {"enabled": true, 
                            "excludeCommands": []}},
  "disabledTools": [],
  "toolCall": {
    "manualApproval": false,
  },
  "mcpTimeoutSeconds" : 10,
  "mcpServers" : [],
  "customProviders": {},
  "ollama" : {
    "host" : "http://localhost",
    "port" : 11434,
    "useTools": false
  },
  "chat" : {
    "welcomeMessage" : "Welcome to ECA! What you have in mind?\n\n"
  },
  "index" : {
    "ignoreFiles" : [ {
      "type" : "gitignore"
    } ]
  }
}
```
