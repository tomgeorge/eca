(ns eca.handlers
  (:require
   [eca.config :as config]
   [eca.db :as db]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]))

(defn initialize [{:keys [db*]} params]
  (logger/logging-task
   :eca/initialize
   (swap! db* assoc
          :client-info (:client-info params)
          :workspace-folders (:workspace-folders params)
          :client-capabilities (:capabilities params))
   {:models (:models @db*)
    :chat-welcome-message "Welcome to ECA! What you have in mind?\n\n"}))

(defn shutdown [{:keys [db*]}]
  (logger/logging-task
   :eca/shutdown
   (reset! db* db/initial-db)
   nil))

(defn chat-prompt [{:keys [messenger db*]} {:keys [message model chat-id]}]
  (logger/logging-task
   :eca/chat-prompt
   (let [config (config/all)
         chat-id (or chat-id
                     (let [new-id (str (random-uuid))]
                       (swap! db* update :chats conj {:id new-id})
                       new-id))]
     (messenger/chat-content-received
      messenger
      {:chat-id chat-id
       :is-complete false
       :role :user
       :content {:type :text
                 :text (str message "\n")}})
     (messenger/chat-content-received
      messenger
      {:chat-id chat-id
       :is-complete false
       :role :system
       :content {:type :temporary-text
                 :text "Processing..."}})
     (llm-api/complete! {:model (or model (:default-model @db*))
                         :message message
                         :config config
                         :on-message-received (fn [{:keys [message finish-reason]}]
                                                (messenger/chat-content-received
                                                 messenger
                                                 {:chat-id chat-id
                                                  :role :assistant
                                                  :is-complete (boolean finish-reason)
                                                  :content {:type :text
                                                            :text message}}))
                         :on-error (fn [e]
                                     (messenger/chat-content-received
                                      messenger
                                      {:chat-id chat-id
                                       :is-complete true
                                       :role :system
                                       :content {:type :text
                                                 :text (str "\nError: " (ex-message e))}}))})
     {:chat-id chat-id
      :status :success})))
