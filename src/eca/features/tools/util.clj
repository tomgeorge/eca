(ns eca.features.tools.util
  (:require
   [clojure.string :as string]
   [eca.shared :as shared]))

(defn workspace-roots-strs [db]
  (->> (:workspace-folders db)
       (map #(shared/uri->filename (:uri %)))
       (string/join "\n")))
