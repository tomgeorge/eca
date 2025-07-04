(ns eca.features.tools.util
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.shared :as shared]))

(defn workspace-roots-strs [db]
  (->> (:workspace-folders db)
       (map #(shared/uri->filename (:uri %)))
       (string/join "\n")))

(defn command-available? [command & args]
  (try
    (zero? (:exit (apply shell/sh (concat [command] args))))
    (catch Exception _ false)))
