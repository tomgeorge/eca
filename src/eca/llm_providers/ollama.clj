(ns eca.llm-providers.ollama
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OLLAMA]")

(def ^:private chat-url "%s/api/chat")
(def ^:private list-models-url "%s/api/tags")

(defn ^:private base-url [host port]
  (or (System/getenv "OLLAMA_API_BASE")
      (str host ":" port)))

(defn list-models [{:keys [host port]}]
  (try
    (let [{:keys [status body]} (http/get
                                 (format list-models-url (base-url host port))
                                 {:throw-exceptions? false
                                  :as :json})]
      (if (= 200 status)
        (do
          (llm-util/log-response logger-tag "api/tags" body)
          (:models body))
        (do
          (logger/warn logger-tag "Unknown status code:" status)
          [])))
    (catch Exception e
      (logger/warn logger-tag "Error listing running models:" (ex-message e))
      [])))

(defn ^:private base-completion-request! [{:keys [url body on-error on-response]}]
  (logger/debug logger-tag (format "Sending body: '%s', url: '%s'" body url))
  (http/post
   url
   {:body (json/generate-string body)
    :throw-exceptions? false
    :async? true
    :as :stream}
   (fn [{:keys [status body]}]
     (try
       (if (not= 200 status)
         (let [body-str (slurp body)]
           (logger/warn logger-tag "Unexpected response status: %s body: %s" status body-str)
           (on-error {:message (format "Ollama response status: %s body: %s" status body-str)}))
         (with-open [rdr (io/reader body)]
           (doseq [[event data] (llm-util/event-data-seq rdr)]
             (on-response event data))))
       (catch Exception e
         (on-error {:exception e}))))
   (fn [e]
     (on-error {:exception e}))))

(defn ^:private ->tools [tools]
  (mapv (fn [tool]
          {:type "function"
           :function (select-keys tool [:name :description :parameters])})
        tools))

(defn completion! [{:keys [model user-prompt context host port past-messages tools]}
                   {:keys [on-message-received on-error on-prepare-tool-call on-tool-called]}]
  (let [messages (concat
                  [{:role "system" :content context}]
                  past-messages
                  [{:role "user" :content user-prompt}])
        body {:model model
              :messages messages
              :think false
              :tools (->tools tools)
              :stream true}
        url (format chat-url (base-url host port))
        on-response-fn (fn handle-response [event data]
                         (llm-util/log-response logger-tag event data)
                         (let [{:keys [message done_reason]} data]
                           (cond
                             (seq (:tool_calls message))
                             (let [function (:function (first (seq (:tool_calls message))))
                                   id (str (random-uuid))
                                   _ (on-prepare-tool-call {:id id
                                                            :name (:name function)
                                                            :argumentsText ""})
                                   response (on-tool-called {:id id
                                                             :name (:name function)
                                                             :arguments (:arguments function)})]
                               (base-completion-request!
                                {:url url
                                 :body (assoc body :messages (concat messages
                                                                     [message]
                                                                     (mapv
                                                                      (fn [{:keys [_type content]}]
                                                                        {:role "tool"
                                                                         :content content})
                                                                      (:contents response))))
                                 :on-error on-error
                                 :on-response handle-response}))

                             done_reason
                             (on-message-received {:type :finish
                                                   :finish-reason done_reason})

                             message
                             (on-message-received {:type :text
                                                   :text (:content message)}))))]
    (base-completion-request!
     {:url url
      :body body
      :on-error on-error
      :on-response on-response-fn})))
