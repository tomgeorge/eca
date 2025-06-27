(ns eca.db
  "Namespace for database state management in the Editor Code Assistant (ECA).

  Defines `initial-db` as the default application state map and `db*` as the atom
  that holds the mutable runtime state.")

(set! *warn-on-reflection* true)

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :chats {}
   :chat-behaviors ["agent" "chat"]
   :chat-default-behavior "chat"
   :models {"o4-mini" {:mcp-tools true
                       :web-search false}
            "o3" {:mcp-tools true
                  :web-search false}
            "gpt-4.1" {:mcp-tools true
                       :web-search true}
            "claude-sonnet-4-0" {:mcp-tools true
                                 :web-search true}
            "claude-opus-4-0" {:mcp-tools true
                               :web-search true}
            "claude-3-5-haiku-latest" {:mcp-tools true
                                       :web-search true}} ;; + ollama local models
   :default-model "o4-mini" ;; unless a ollama model is running.
   })

(defonce db* (atom initial-db))

(comment
  (:client (:clojure-mcp (:mcp-clients @db*))))
