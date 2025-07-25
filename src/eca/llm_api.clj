(ns eca.llm-api
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.llm-providers.openai :as llm-providers.openai]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[LLM-API]")

(defn extra-models [config]
  (llm-providers.ollama/list-models {:host (:host (:ollama config))
                                     :port (:port (:ollama config))}))

(defn refine-file-context [path]
  ;; TODO ask LLM for the most relevant parts of the path
  (slurp path))

(defn ^:private anthropic-api-key [config]
  (or (:anthropicApiKey config)
      (config/get-env "ANTHROPIC_API_KEY")))

(defn ^:private anthropic-api-url []
  (or (config/get-env "ANTHROPIC_API_URL")
      llm-providers.anthropic/base-url))

(defn ^:private openai-api-key [config]
  (or (:openaiapikey config)
      (config/get-env "OPENAI_API_KEY")))

(defn ^:private openai-api-url []
  (or (config/get-env "OPENAI_API_URL")
      llm-providers.openai/base-url))

(defn default-model
  "Returns the default LLM model checking this waterfall:
  - Any custom provider with defaultModel set
  - Anthropic api key set
  - Openai api key set
  - Ollama first model if running
  - Anthropic default model."
  [db config]
  (let [[decision model]
        (or (when-let [custom-provider-default-model (first (keep (fn [[model config]]
                                                                    (when (and (:custom-provider? config)
                                                                               (:default-model? config))
                                                                      model))
                                                                  (:models db)))]
              [:custom-provider-default-model custom-provider-default-model])
            (when-let [ollama-model (first (filter #(string/starts-with? % config/ollama-model-prefix) (keys (:models db))))]
              [:ollama-running ollama-model])
            (when (anthropic-api-key config)
              [:api-key-found "claude-sonnet-4-0"])
            (when (openai-api-key config)
              [:api-key-found "o4-mini"])
            [:default "claude-sonnet-4-0"])]
    (logger/info logger-tag (format "Default LLM model '%s' decision '%s'" model decision))
    model))

(defn ^:private tool->llm-tool [tool]
  (assoc (select-keys tool [:name :description :parameters])
         :type "function"))

(defn complete!
  [{:keys [model model-config instructions user-prompt config on-first-response-received
           on-message-received on-error on-prepare-tool-call on-tool-called on-reason
           past-messages tools]}]
  (let [first-response-received* (atom false)
        emit-first-message-fn (fn [& args]
                                (when-not @first-response-received*
                                  (reset! first-response-received* true)
                                  (apply on-first-response-received args)))
        on-message-received-wrapper (fn [& args]
                                      (apply emit-first-message-fn args)
                                      (apply on-message-received args))
        on-prepare-tool-call-wrapper (fn [& args]
                                       (apply emit-first-message-fn args)
                                       (apply on-prepare-tool-call args))
        on-error-wrapper (fn [{:keys [exception] :as args}]
                           (when-not (:silent? (ex-data exception))
                             (logger/error args)
                             (on-error args)))
        tools (when (:tools model-config)
                (mapv tool->llm-tool tools))
        web-search (:web-search model-config)
        custom-providers (:customProviders config)
        custom-models (set (mapcat (fn [[k v]]
                                     (map #(str (name k) "/" %) (:models v)))
                                   custom-providers))
        callbacks {:on-message-received on-message-received-wrapper
                   :on-error on-error-wrapper
                   :on-prepare-tool-call on-prepare-tool-call-wrapper
                   :on-tool-called on-tool-called
                   :on-reason on-reason}]
    (cond
      (contains? #{"o4-mini"
                   "o3"
                   "gpt-4.1"} model)
      (llm-providers.openai/completion!
       {:model model
        :instructions instructions
        :user-prompt user-prompt
        :past-messages past-messages
        :tools tools
        :web-search web-search
        :api-url (openai-api-url)
        :api-key (openai-api-key config)}
       callbacks)

      (contains? #{"claude-sonnet-4-0"
                   "claude-opus-4-0"
                   "claude-3-5-haiku-latest"} model)
      (llm-providers.anthropic/completion!
       {:model model
        :instructions instructions
        :user-prompt user-prompt
        :past-messages past-messages
        :tools tools
        :web-search web-search
        :api-url (anthropic-api-url)
        :api-key (anthropic-api-key config)}
       callbacks)

      (string/starts-with? model config/ollama-model-prefix)
      (llm-providers.ollama/completion!
       {:host (-> config :ollama :host)
        :port (-> config :ollama :port)
        :model (string/replace-first model config/ollama-model-prefix "")
        :instructions instructions
        :user-prompt user-prompt
        :past-messages past-messages
        :tools tools}
       callbacks)

      (contains? custom-models model)
      (let [[provider model] (string/split model #"/")
            provider-config (get custom-providers (keyword provider))
            provider-fn (case (:api provider-config)
                          "openai" llm-providers.openai/completion!
                          "anthropic" llm-providers.anthropic/completion!
                          (on-error-wrapper {:msg (format "Unknown custom model %s for provider %s" (:api provider-config) provider)}))
            url (or (:url provider-config) (config/get-env (:urlEnv provider-config)))
            key (or (:key provider-config) (config/get-env (:keyEnv provider-config)))]
        (provider-fn
         {:model model
          :instructions instructions
          :user-prompt user-prompt
          :past-messages past-messages
          :web-search web-search
          :tools tools
          :api-url url
          :api-key key}
         callbacks))

      :else
      (on-error-wrapper {:msg (str "ECA Unsupported model: " model)}))))
