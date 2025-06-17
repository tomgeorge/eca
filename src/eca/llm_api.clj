(ns eca.llm-api
  (:require
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [eca.llm-providers.openai :as llm-providers.openai]))

(set! *warn-on-reflection* true)

(defn refine-file-context [path]
  ;; TODO ask LLM for the most relevant parts of the path
  (slurp path))

(defn complete! [{:keys [model context user-prompt config on-message-received on-error]}]
  (case model
    ("o4-mini"
     "gpt-4.1")
    (llm-providers.openai/completion!
     {:model model
      :context context
      :user-prompt user-prompt
      :api-key (:openai-api-key config)}
     {:on-message-received on-message-received
      :on-error on-error})
    ("claude-sonnet-4-0"
     "claude-opus-4-0"
     "claude-3-5-haiku-latest")
    (llm-providers.anthropic/completion!
     {:model model
      :context context
      :user-prompt user-prompt
      :api-key (:anthropic-api-key config)}
     {:on-message-received on-message-received
      :on-error on-error})
    (on-error {:msg (str "ECA Unsupported model: " model)})))
