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
    (let [rid (llm-util/gen-rid)
          {:keys [status body]} (http/get
                                 (format list-models-url (base-url host port))
                                 {:throw-exceptions? false
                                  :as :json})]
      (if (= 200 status)
        (do
          (llm-util/log-response logger-tag rid "api/tags" body)
          (:models body))
        (do
          (logger/warn logger-tag "Unknown status code:" status)
          [])))
    (catch Exception e
      (logger/warn logger-tag "Error listing running models:" (ex-message e))
      [])))

(defn ^:private base-completion-request! [{:keys [rid url body on-error on-response]}]
  (llm-util/log-request logger-tag rid url body)
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
             (llm-util/log-response logger-tag rid event data)
             (on-response rid event data))))
       (catch Exception e
         (on-error {:exception e}))))
   (fn [e]
     (on-error {:exception e}))))

(defn ^:private ->tools [tools]
  (mapv (fn [tool]
          {:type "function"
           :function (select-keys tool [:name :description :parameters])})
        tools))

(defn ^:private past-messages->messages [past-messages context]
  (concat
   [{:role "system" :content context}]
   (mapv (fn [{:keys [role content] :as msg}]
           (case role
             "tool_call" {:role "assistant" :tool-calls [{:type "function"
                                                          :function content}]}
             "tool_call_output" {:role "tool" :content (llm-util/stringfy-tool-result content)}
             msg))
         past-messages)))

(defn completion! [{:keys [model user-prompt instructions host port past-messages tools]}
                   {:keys [on-message-received on-error on-prepare-tool-call on-tool-called]}]
  (let [messages (concat
                  (past-messages->messages past-messages instructions)
                  [{:role "user" :content user-prompt}])
        body {:model model
              :messages messages
              :think false
              :tools (->tools tools)
              :stream true}
        url (format chat-url (base-url host port))
        tool-calls* (atom {})
        on-response-fn (fn handle-response [rid _event data]
                         (let [{:keys [message done_reason]} data]
                           (cond
                             (seq (:tool_calls message))
                             (let [function (:function (first (seq (:tool_calls message))))
                                   call-id (str (random-uuid))
                                   tool-call {:id call-id
                                              :name (:name function)
                                              :arguments (:arguments function)}]
                               (on-prepare-tool-call (assoc tool-call :arguments-text ""))
                               (swap! tool-calls* assoc rid tool-call))

                             done_reason
                             (if-let [tool-call (get @tool-calls* rid)]
                               (let [{:keys [new-messages]} (on-tool-called tool-call)]
                                 (swap! tool-calls* dissoc rid)
                                 (base-completion-request!
                                  {:rid (llm-util/gen-rid)
                                   :url url
                                   :body (assoc body :messages (past-messages->messages new-messages instructions))
                                   :on-error on-error
                                   :on-response handle-response}))
                               (on-message-received {:type :finish
                                                     :finish-reason done_reason}))

                             message
                             (on-message-received {:type :text
                                                   :text (:content message)}))))]
    (base-completion-request!
     {:rid (llm-util/gen-rid)
      :url url
      :body body
      :on-error on-error
      :on-response on-response-fn})))
