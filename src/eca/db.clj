(ns eca.db)

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :chats []
   :chat-behavior :agent
   :models ["o4-mini"]
   :default-model "o4-mini"})

(defonce db* (atom initial-db))
