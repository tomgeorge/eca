(ns eca.llm-providers.anthropic
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[ANTHROPIC]")

(def ^:private url "https://api.anthropic.com/v1/messages")

(defn ^:private ->tools [tools]
  (mapv (fn [tool]
          (assoc (select-keys tool [:name :description])
                 :input_schema (:parameters tool))) tools))

(defn ^:private base-request! [{:keys [body api-key on-error on-response]}]
  (let [api-key (or api-key
                    (System/getenv "ANTHROPIC_API_KEY"))]
    (http/post
     url
     {:headers {"x-api-key" api-key
                "anthropic-version" "2023-06-01"
                "Content-Type" "application/json"}
      :body (json/generate-string body)
      :throw-exceptions? false
      :async? true
      :as :stream}
     (fn [{:keys [status body]}]
       (try
         (if (not= 200 status)
           (let [body-str (slurp body)]
             (logger/warn logger-tag "Unexpected response status: %s body: %s" status body-str)
             (on-error {:message (format "Anthropic response status: %s body: %s" status body-str)}))
           (with-open [rdr (io/reader body)]
             (doseq [[event data] (llm-util/event-data-seq rdr)]
               (on-response event data))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))

(defn completion!
  [{:keys [model user-prompt temperature context max-tokens
           api-key past-messages tools]
    :or {max-tokens 1024
         temperature 1.0}}
   {:keys [on-message-received on-error]}]
  (let [body {:model model
              :messages (conj past-messages {:role "user" :content user-prompt})
              :max_tokens max-tokens
              :temperature temperature
              ;; TODO support :thinking
              :stream true
              :tools (->tools tools)
              :system context}
        on-response-fn (fn handle-response [event data]
                         (case event
                           "content_block_delta" (case (-> data :delta :type)
                                                   "text_delta" (on-message-received {:message (-> data :delta :text)})
                                                   (logger/warn "Unkown response delta type" (-> data :delta :type)))
                           "message_stop" (on-message-received {:finish-reason (:type data)})
                           nil))]
    (base-request!
     {:body body
      :api-key api-key
      :on-error on-error
      :on-response on-response-fn})))
