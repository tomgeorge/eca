(ns eca.llm-api
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.llm-providers.openai :as llm-providers.openai]))

(set! *warn-on-reflection* true)

(defn extra-models [config]
  (llm-providers.ollama/list-running-models {:host (:host (:ollama config))
                                             :port (:port (:ollama config))}))

(defn refine-file-context [path]
  ;; TODO ask LLM for the most relevant parts of the path
  (slurp path))

(defn ^:private mcp-tool->llm-tool [mcp-tool]
  (assoc (select-keys mcp-tool [:name :description :parameters])
         :type "function"))

(defn complete!
  [{:keys [model model-config context user-prompt config on-first-message-received
           on-message-received on-error on-tool-called
           past-messages mcp-tools]}]
  (let [first-message-received* (atom false)
        on-message-received-wrapper (fn [& args]
                                      (when-not @first-message-received*
                                        (reset! first-message-received* true)
                                        (apply on-first-message-received args))
                                      (apply on-message-received args))
        tools (when (:mcp-tools model-config)
                (map mcp-tool->llm-tool mcp-tools))
        web-search (:web-search model-config)]
    (cond
      (contains? #{"o4-mini"
                   "o3"
                   "gpt-4.1"} model)
      (llm-providers.openai/completion!
       {:model model
        :context context
        :user-prompt user-prompt
        :past-messages past-messages
        :tools tools
        :web-search web-search
        :api-key (:openai-api-key config)}
       {:on-message-received on-message-received-wrapper
        :on-error on-error
        :on-tool-called on-tool-called})

      (contains? #{"claude-sonnet-4-0"
                   "claude-opus-4-0"
                   "claude-3-5-haiku-latest"} model)
      (llm-providers.anthropic/completion!
       {:model model
        :context context
        :user-prompt user-prompt
        :past-messages past-messages
        :tools tools
        :web-search web-search
        :api-key (:anthropic-api-key config)}
       {:on-message-received on-message-received-wrapper
        :on-error on-error
        :on-tool-called on-tool-called})

      (string/starts-with? model config/ollama-model-prefix)
      (llm-providers.ollama/completion!
       {:host (-> config :ollama :host)
        :port (-> config :ollama :port)
        :model (string/replace-first model config/ollama-model-prefix "")
        :past-messages past-messages
        :context context
        :tools tools
        :user-prompt user-prompt}
       {:on-message-received on-message-received-wrapper
        :on-error on-error
        :on-tool-called on-tool-called})

      :else
      (on-error {:msg (str "ECA Unsupported model: " model)}))))
