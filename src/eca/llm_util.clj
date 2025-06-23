(ns eca.llm-util
  (:require
   [cheshire.core :as json]
   [clojure.string :as string])
  (:import
   [java.io BufferedReader]))

(defn event-data-seq [^BufferedReader rdr]
  (when-let [event (.readLine rdr)]
    (when (string/starts-with? event "event:")
      (when-let [data (.readLine rdr)]
        (.readLine rdr) ;; blank line
        (when (string/starts-with? data "data:")
          (cons [(subs event 7)
                 (json/parse-string (subs data 6) true)]
                (lazy-seq (event-data-seq rdr))))))))
