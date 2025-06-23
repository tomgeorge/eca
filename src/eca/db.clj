(ns eca.db)

(set! *warn-on-reflection* true)

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :chats {}
   :chat-behaviors ["agent" "chat"]
   :chat-default-behavior "chat"
   :models {"o4-mini" {:tools true}
            "gpt-4.1" {:tools true}
            "claude-sonnet-4-0" {:tools true}
            "claude-opus-4-0" {:tools true}
            "claude-3-5-haiku-latest" {:tools true}} ;; + ollama local models
   :default-model "o4-mini" ;; unless a ollama model is running.
   })

(defonce db* (atom initial-db))
