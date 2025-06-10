(ns eca.llm-providers.openai
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hato.client :as http]))

(def ^:private url "https://api.openai.com/v1/chat/completions")

(defn ^:private raw-data->messages [data]
  (let [{:keys [choices]} (json/parse-string data true)]
    (map (fn [{:keys [delta finish_reason]}]
           (cond-> {}
             (:content delta) (assoc :message (:content delta))
             finish_reason (assoc :finish-reason finish_reason)))
         choices)))

(defn completion! [{:keys [model messages temperature api-key]
                    :or {temperature 1.0}}
                   {:keys [on-message-received on-error]}]
  (let [body {:model model
              :messages messages
              :temperature temperature
              :stream true}
        api-key (or api-key
                    (System/getenv "OPENAI_API_KEY"))]
    (http/post
     url
     {:headers {"Authorization" (str "Bearer " api-key)
                "Content-Type" "application/json"}
      :body (json/generate-string body)
      :async? true
      :as :stream}
     (fn [{:keys [status body]}]
       (try
         (if (not= 200 status)
           (on-error status)
           (with-open [rdr (io/reader body)]
             (doseq [line (line-seq rdr)]
               (when (str/starts-with? line "data: ")
                 (let [data (subs line 6)]
                   (when-not (= "[DONE]" data)
                     (doseq [message (raw-data->messages data)]
                       (on-message-received message))))))))
         (catch Exception e
           ;; TODO improve error handling
           (println "-->" e))))
     (fn [e]
       (on-error e)))))
