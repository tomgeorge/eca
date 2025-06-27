(ns eca.llm-providers.openai
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OPENAI]")

(def ^:private openai-url "https://api.openai.com")
(def ^:private responses-path "/v1/responses")

(defn ^:private url [path]
  (format "%s%s"
          (or (System/getenv "OPENAI_API_URL")
              openai-url)
          path))

(defn ^:private base-completion-request! [{:keys [body api-key on-error on-response]}]
  (let [api-key (or api-key
                    (System/getenv "OPENAI_API_KEY"))
        url (url responses-path)]
    (logger/debug logger-tag (format "Sending input: '%s' instructions: '%s' tools: '%s' url: '%s'"
                                     (:input body)
                                     (:instructions body)
                                     (:tools body)
                                     url))
    (http/post
     url
     {:headers {"Authorization" (str "Bearer " api-key)
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
             (on-error {:message (format "OpenAI response status: %s body: %s" status body-str)}))
           (with-open [rdr (io/reader body)]
             (doseq [[event data] (llm-util/event-data-seq rdr)]
               (on-response event data))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))

(defn completion! [{:keys [model user-prompt context temperature api-key past-messages tools web-search]
                    :or {temperature 1.0}}
                   {:keys [on-message-received on-error on-tool-called on-reason]}]
  (let [input (conj past-messages {:role "user" :content user-prompt})
        tools (cond-> tools
                web-search (conj {:type "web_search_preview"}))
        body {:model model
              :input input
              :user (str (System/getProperty "user.name") "@ECA")
              :instructions context
              :temperature temperature
              :tools tools
              :stream true}
        on-response-fn (fn handle-response [event data]
                         (llm-util/log-response logger-tag event data)
                         (case event
                           ;; text
                           "response.output_text.delta" (on-message-received {:type :text
                                                                              :text (:delta data)})
                           ;; tools
                           "response.output_item.done" (case (:type (:item data))
                                                         "function_call" (let [function-name (-> data :item :name)
                                                                               function-args (-> data :item :arguments)
                                                                               response (on-tool-called {:id (-> data :item :call_id)
                                                                                                         :name function-name
                                                                                                         :arguments (json/parse-string function-args)})]
                                                                           (base-completion-request!
                                                                            {:body (assoc body :input (concat input
                                                                                                              [{:type "function_call"
                                                                                                                :call_id (-> data :item :call_id)
                                                                                                                :name function-name
                                                                                                                :arguments function-args}]
                                                                                                              (mapv
                                                                                                               (fn [{:keys [_type content]}]
                                                                                                                  ;; TODO handle different types
                                                                                                                 {:type "function_call_output"
                                                                                                                  :call_id (-> data :item :call_id)
                                                                                                                  :output content})
                                                                                                               (:contents response))))
                                                                             :api-key api-key
                                                                             :on-error on-error
                                                                             :on-response handle-response}))
                                                         "reasoning" (on-reason {:status :finished})
                                                         nil)
                           ;; URL mentioned
                           "response.output_text.annotation.added" (case (-> data :annotation :type)
                                                                     "url_citation" (on-message-received
                                                                                     {:type :url
                                                                                      :title (-> data :annotation :title)
                                                                                      :url (-> data :annotation :url)})
                                                                     nil)
                           ;; reasoning
                           "response.output_item.added" (case (-> data :item :type)
                                                          "reasoning" (on-reason {:status :started})
                                                          nil)

                           ;; done
                           "response.completed" (when-not (= "function_call" (-> data :response :output last :type))
                                                  (on-message-received {:type :finish
                                                                        :finish-reason (-> data :response :status)}))
                           nil))]
    (base-completion-request!
     {:body body
      :api-key api-key
      :on-error on-error
      :on-response on-response-fn})))
