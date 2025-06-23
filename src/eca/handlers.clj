(ns eca.handlers
  (:require
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.features.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(defn ^:private initialize-extra-models! [db*]
  (let [config (config/all @db*)]
    (when-let [ollama-models (seq (llm-api/extra-models config))]
      (swap! db* update :models merge
             (reduce
              (fn [models {:keys [model]}]
                (assoc models
                       (str config/ollama-model-prefix model)
                       {:mcp-tools (get-in config [:ollama :useTools] false)}))
              {}
              ollama-models)))))

(defn initialize [{:keys [db*]} params]
  (logger/logging-task
   :eca/initialize
   (swap! db* assoc
          :client-info (:client-info params)
          :workspace-folders (:workspace-folders params)
          :client-capabilities (:capabilities params)
          :chat-behavior (or (-> params :initialization-options :chat-behavior) (:chat-behavior @db*)))
   (let [config (config/all @db*)]
     (initialize-extra-models! db*)
     ;; TODO initialize async with progress support
     (f.mcp/initialize! db* config)
     {:models (keys (:models @db*))
      :chat-default-model (f.chat/default-model @db*)
      :chat-behaviors (:chat-behaviors @db*)
      :chat-default-behavior (:chat-default-behavior @db*)
      :chat-welcome-message (:welcomeMessage (:chat config))})))

(defn shutdown [{:keys [db*]}]
  (logger/logging-task
   :eca/shutdown
   (f.mcp/shutdown! db*)
   (reset! db* db/initial-db)
   nil))

(defn chat-prompt [{:keys [messenger db*]} params]
  (logger/logging-task
   :eca/chat-prompt
   (let [config (config/all @db*)]
     (f.chat/prompt params db* messenger config))))

(defn chat-query-context [{:keys [db*]} params]
  (logger/logging-task
   :eca/chat-query-context
   (f.chat/query-context params db*)))
