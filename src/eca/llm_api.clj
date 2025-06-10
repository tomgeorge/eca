(ns eca.llm-api
  (:require
   [eca.llm-providers.openai :as llm-providers.openai]))

(set! *warn-on-reflection* true)

(defn complete! [{:keys [model message config on-message-received on-error]}]
  (case model
    "o4-mini" (llm-providers.openai/completion!
               {:model model
                :messages [{:role "user" :content message}]
                :api-key (:openai-api-key config)}
               {:on-message-received on-message-received
                :on-error on-error})
    (on-error (str "Unsupported model: " model))))
