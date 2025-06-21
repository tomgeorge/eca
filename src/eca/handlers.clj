(ns eca.handlers
  (:require
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]))

(defn ^:private initialize-extra-models! [db*]
  (let [config (config/all @db*)]
    (when-let [ollama-models (seq (llm-api/extra-models config))]
      (swap! db* update :models concat (map #(str config/ollama-model-prefix (:model %)) ollama-models)))))

(defn initialize [{:keys [db*]} params]
  (logger/logging-task
   :eca/initialize
   (let [config (config/all @db*)]
     (swap! db* assoc
            :client-info (:client-info params)
            :workspace-folders (:workspace-folders params)
            :client-capabilities (:capabilities params)
            :chat-behavior (or (-> params :initialization-options :chat-behavior) (:chat-behavior @db*)))
     (initialize-extra-models! db*)
     {:models (:models @db*)
      :chat-default-model (f.chat/default-model @db*)
      :chat-behaviors (:chat-behaviors @db*)
      :chat-default-behavior (:chat-default-behavior @db*)
      :chat-welcome-message (:welcome-message (:chat config))})))

(defn shutdown [{:keys [db*]}]
  (logger/logging-task
   :eca/shutdown
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
