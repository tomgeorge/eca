(ns eca.db)

(set! *warn-on-reflection* true)

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :chats {}
   :chat-behaviors ["agent" "chat"]
   :chat-default-behavior "chat"
   :models {"o4-mini" {:tools true
                       :web-search false}
            "o3" {:tools true
                  :web-search false}
            "gpt-4.1" {:tools true
                       :web-search true}
            "claude-sonnet-4-0" {:tools true
                                 :web-search true}
            "claude-opus-4-0" {:tools true
                               :web-search true}
            "claude-3-5-haiku-latest" {:tools true
                                       :web-search true}} ;; + ollama local models
   :default-model "o4-mini" ;; unless a ollama model is running.
   :mcp-clients {}})

(defonce db* (atom initial-db))
