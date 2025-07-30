(ns eca.features.tools.util
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.shared :as shared]))

(defn single-text-content [text & [error]]
  {:error (boolean error)
   :contents [{:type :text
               :content text}]})

(defn workspace-roots-strs [db]
  (->> (:workspace-folders db)
       (map #(shared/uri->filename (:uri %)))
       (string/join "\n")))

(defn command-available? [command & args]
  (try
    (zero? (:exit (apply shell/sh (concat [command] args))))
    (catch Exception _ false)))

(defn invalid-arguments [arguments validator]
  (first (keep (fn [[key pred error-msg]]
                 (let [value (get arguments key)]
                   (when-not (pred value)
                     (single-text-content (string/replace error-msg (str "$" key) (str value))
                                          :error))))
               validator)))
