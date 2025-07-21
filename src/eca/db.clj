(ns eca.db)

(set! *warn-on-reflection* true)

(def ^:private one-million 1000000)

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :chats {}
   :chat-behaviors ["agent" "chat"]
   :chat-default-behavior "agent"
   :models {"o4-mini" {:tools true
                       :web-search false
                       :input-token-cost (/ 1.10 one-million)
                       :output-token-cost (/ 4.40 one-million)}
            "o3" {:tools true
                  :web-search false
                  :input-token-cost (/ 2.0 one-million)
                  :output-token-cost (/ 8.0 one-million)}
            "gpt-4.1" {:tools true
                       :web-search true
                       :input-token-cost (/ 2.0 one-million)
                       :output-token-cost (/ 8.0 one-million)}
            "claude-sonnet-4-0" {:tools true
                                 :web-search true
                                 :input-token-cost (/ 3.0 one-million)
                                 :output-token-cost (/ 15.0 one-million)}
            "claude-opus-4-0" {:tools true
                               :web-search true
                               :input-token-cost (/ 15.0 one-million)
                               :output-token-cost (/ 75.0 one-million)}
            "claude-3-5-haiku-latest" {:tools true
                                       :web-search true
                                       :input-token-cost (/ 0.8 one-million)
                                       :output-token-cost (/ 4.0 one-million)}} ;; + ollama local models + custom provider models
   :mcp-clients {}})

(defonce db* (atom initial-db))
