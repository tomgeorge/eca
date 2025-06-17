(ns eca.handlers
  (:require
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.logger :as logger]
   [eca.llm-api :as llm-api]))

(defn initialize [{:keys [db*]} params]
  (logger/logging-task
      :eca/initialize
      (let [config (config/all)]
        (swap! db* assoc
               :client-info (:client-info params)
               :workspace-folders (:workspace-folders params)
               :client-capabilities (:capabilities params)
               :chat-behavior (or (-> params :initialization-options :chat-behavior) (:chat-behavior @db*)))
        {:models (llm-api/all-models @db*)
         :chat-behavior (:chat-behavior @db*)
         :chat-welcome-message (:welcome-message (:chat config))})))

(defn shutdown [{:keys [db*]}]
  (logger/logging-task
   :eca/shutdown
   (reset! db* db/initial-db)
   nil))

(defn chat-prompt [{:keys [messenger db*]} params]
  (logger/logging-task
   :eca/chat-prompt
   (let [config (config/all)]
     (f.chat/prompt params db* messenger config))))

(defn chat-query-context [{:keys [db*]} params]
  (logger/logging-task
   :eca/chat-query-context
   (f.chat/query-context params db*)))
