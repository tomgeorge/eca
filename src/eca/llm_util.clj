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
                  (cons [nil (json/parse-string line true)]
                        (lazy-seq (next-group)))

                  :else
                  (recur event-line)))))]
    (next-group)))

(defn log-response [tag event data]
  (logger/debug tag event data))
