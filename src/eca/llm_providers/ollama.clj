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
(def ^:private list-running-models-url "%s/api/ps")

(defn ^:private base-url [host port]
  (or (System/getenv "OLLAMA_API_BASE")
      (str host ":" port)))

(defn list-running-models [{:keys [host port]}]
  (try
    (let [{:keys [status body]} (http/get
                                 (format list-running-models-url (base-url host port))
                                 {:throw-exceptions? false
                                  :as :json})]
      (if (= 200 status)
        (do
          (llm-util/log-response logger-tag "api_ps" body)
          (:models body))
        (do
          (logger/warn logger-tag "Unknown status code:" status)
          [])))
    (catch Exception e
      (logger/warn logger-tag "Error listing running models:" (ex-message e))
      [])))

(defn ^:private ->message-with-context [context user-prompt]
  (format "%s\nThe user is asking: '%s'" context user-prompt))

(defn ^:private base-completion-request! [{:keys [url body on-error on-response]}]
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
           (doseq [line (line-seq rdr)]
             (on-response (json/parse-string line true)))))
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
                   {:keys [on-message-received on-error _on-tool-called]}]
  (let [messagess (if (empty? past-messages)
                    [{:role "user" :content (->message-with-context context user-prompt)}]
                    (conj past-messages {:role "user" :content user-prompt}))
        body {:model model
              :messages messagess
              :tools (->tools tools)
              :stream true}
        url (format chat-url (base-url host port))
        on-response-fn (fn handle-response [data]
                         (llm-util/log-response logger-tag "chat" data)
                         (let [{:keys [message done_reason]} data]
                           (on-message-received
                            (cond-> {}
                              message (assoc :type :text :text (:content message))
                              done_reason (assoc :type :finish :finish-reason done_reason)))))]
    (base-completion-request!
     {:url url
      :body body
      :on-error on-error
      :on-response on-response-fn})))
