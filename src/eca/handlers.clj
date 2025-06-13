(ns eca.handlers
  (:require
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.logger :as logger]))

(defn initialize [{:keys [db*]} params]
  (logger/logging-task
   :eca/initialize
   (swap! db* assoc
          :client-info (:client-info params)
          :workspace-folders (:workspace-folders params)
          :client-capabilities (:capabilities params)
          :chat-behavior (or (-> params :initialization-options :chat-behavior) (:chat-behavior @db*)))
   {:models (:models @db*)
    :chat-behavior (:chat-behavior @db*)
    :chat-welcome-message "Welcome to ECA! What you have in mind?\n\n"}))

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
