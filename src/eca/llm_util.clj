(ns eca.llm-util
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.logger :as logger])
  (:import
   [java.io BufferedReader]))

(defn event-data-seq [^BufferedReader rdr]
  (letfn [(next-group []
            (loop [event-line nil]
              (let [line (.readLine rdr)]
                (cond
                  ;; EOF
                  (nil? line)
                  nil

                  ;; skip blank lines
                  (string/blank? line)
                  (recur event-line)

                  ;; event: <event>
                  (string/starts-with? line "event:")
                  (recur line)

                  ;; data: <data>
                  (string/starts-with? line "data:")
                  (let [data-str (subs line 6)]
                    (if (= data-str "[DONE]")
                      (recur event-line) ; skip [DONE]
                      (let [event-type (if event-line
                                         (subs event-line 7)
                                         (-> (json/parse-string data-str true)
                                             :type))]
                        (cons [event-type (json/parse-string data-str true)]
                              (lazy-seq (next-group))))))

                  ;; data directly
                  (string/starts-with? line "{")
                  (cons ["data" (json/parse-string line true)]
                        (lazy-seq (next-group)))

                  :else
                  (recur event-line)))))]
    (next-group)))

(defn gen-rid
  "Generates a request-id for tracking requests"
  []
  (str (rand-int 9999)))

(defn stringfy-tool-result [result]
  (reduce
   #(str %1 (:content %2) "\n")
   ""
   (-> result :output :contents)))

(defn log-request [tag rid url body]
  (logger/debug tag (format "[%s] Sending body: '%s', url: '%s'" rid body url)))

(defn log-response [tag rid event data]
  (logger/debug tag (format "[%s] %s %s" rid event data)))
