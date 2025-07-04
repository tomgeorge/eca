(ns eca.llm-providers.anthropic
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[ANTHROPIC]")

(def ^:private anthropic-url "https://api.anthropic.com")
(def ^:private messages-path "/v1/messages")

(defn ^:private url [path]
  (format "%s%s"
          (or (System/getenv "ANTHROPIC_API_URL")
              anthropic-url)
          path))

(defn ^:private ->tools [tools web-search]
  (cond->
   (mapv (fn [tool]
           (assoc (select-keys tool [:name :description])
                  :input_schema (:parameters tool))) tools)
    web-search (conj {:type "web_search_20250305"
                      :name "web_search"
                      :max_uses 10
                      :cache_control {:type "ephemeral"}})))

(defn ^:private base-request! [{:keys [rid body api-key content-block* on-error on-response]}]
  (let [api-key (or api-key
                    (System/getenv "ANTHROPIC_API_KEY"))
        url (url messages-path)]
    (llm-util/log-request logger-tag rid url body)
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
               (llm-util/log-response logger-tag rid event data)
               (on-response event data content-block*))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))

(defn ^:private ->messages-with-history [past-messages user-prompt]
  (conj (mapv (fn [{:keys [role content] :as msg}]
                (case role
                  "tool_call" {:role "assistant"
                               :content [{:type "tool_use"
                                          :id (:id content)
                                          :name (:name content)
                                          :input (or (:arguments content) {})}]}

                  "tool_call_output"
                  {:role "user"
                   :content [{:type "tool_result"
                              :tool_use_id (:id content)
                              :content (llm-util/stringfy-tool-result content)}]}
                  msg))
              past-messages)
        ;; TODO add cache_control to last non thinking message
        {:role "user" :content [{:type :text
                                 :text user-prompt
                                 :cache_control {:type "ephemeral"}}]}))

(defn completion!
  [{:keys [model user-prompt temperature context max-tokens
           api-key past-messages tools web-search]
    :or {max-tokens 1024
         temperature 1.0}}
   {:keys [on-message-received on-error on-prepare-tool-call on-tool-called]}]
  (let [messages (->messages-with-history past-messages user-prompt)
        body {:model model
              :messages messages
              :max_tokens max-tokens
              :temperature temperature
              ;; TODO support :thinking
              :stream true
              :tools (->tools tools web-search)
              :system [{:type "text" :text context :cache_control {:type "ephemeral"}}]}
        on-response-fn
        (fn handle-response [event data content-block*]
          (case event
            "content_block_start" (case (-> data :content_block :type)
                                    "tool_use" (do
                                                 (on-prepare-tool-call {:name (-> data :content_block :name)
                                                                        :id (-> data :content_block :id)
                                                                        :argumentsText ""})
                                                 (swap! content-block* assoc (:index data) (:content_block data)))

                                    nil)
            "content_block_delta" (case (-> data :delta :type)
                                    "text_delta" (on-message-received {:type :text
                                                                       :text (-> data :delta :text)})
                                    "input_json_delta" (let [text (-> data :delta :partial_json)
                                                             _ (swap! content-block* update-in [(:index data) :input-json] str text)
                                                             content-block (get @content-block* (:index data))]
                                                         (on-prepare-tool-call {:name (:name content-block)
                                                                                :id (:id content-block)
                                                                                :argumentsText text}))
                                    "citations_delta" (case (-> data :delta :citation :type)
                                                        "web_search_result_location" (on-message-received
                                                                                      {:type :url
                                                                                       :title (-> data :delta :citation :title)
                                                                                       :url (-> data :delta :citation :url)})
                                                        nil)
                                    (logger/warn "Unkown response delta type" (-> data :delta :type)))
            "message_delta" (case (-> data :delta :stop_reason)
                              "tool_use" (doseq [content-block (vals @content-block*)]
                                           (when (= "tool_use" (:type content-block))
                                             (let [function-name (:name content-block)
                                                   function-args (:input-json content-block)
                                                   response (on-tool-called {:id (:id content-block)
                                                                             :name function-name
                                                                             :arguments (json/parse-string function-args)})
                                                   messages (concat messages
                                                                    [{:role "assistant"
                                                                      :content [(dissoc content-block :input-json)]}]
                                                                    (mapv
                                                                     (fn [{:keys [_type content]}]
                                                                       {:role "user"
                                                                        :content [{:type "tool_result"
                                                                                   :tool_use_id (:id content-block)
                                                                                   :content content}]})
                                                                     (:contents response)))]
                                               (base-request!
                                                {:rid (llm-util/gen-rid)
                                                 :body (assoc body :messages messages)
                                                 :api-key api-key
                                                 :content-block* (atom nil)
                                                 :on-error on-error
                                                 :on-response handle-response}))))
                              "end_turn" (on-message-received {:type :finish
                                                               :finish-reason (-> data :delta :stop_reason)})
                              nil)
            nil))]
    (base-request!
     {:rid (llm-util/gen-rid)
      :body body
      :api-key api-key
      :content-block* (atom nil)
      :on-error on-error
      :on-response on-response-fn})))
