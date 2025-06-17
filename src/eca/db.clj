(ns eca.db)

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :chats []
   :chat-behavior :agent
   :models ["o4-mini"
            "gpt-4.1"
            "claude-sonnet-4-0"
            "claude-opus-4-0"
            "claude-3-5-haiku-latest"]
   :default-model "o4-mini"})

(defonce db* (atom initial-db))
