(ns eca.shared
  (:require
   [clojure.string :as string])
  (:import
   [java.net URI]
   [java.nio.file Paths]))

(set! *warn-on-reflection* true)

(defn uri->filename [uri]
  (let [uri (URI. uri)]
    (-> uri Paths/get .toString
        ;; WINDOWS drive letters
        (string/replace #"^[a-z]:\\" string/upper-case))))

(defn update-last [coll f]
  (if (seq coll)
    (update coll (dec (count coll)) f)
    coll))

(defn deep-merge [v & vs]
  (letfn [(rec-merge [v1 v2]
                     (if (and (map? v1) (map? v2))
                       (merge-with deep-merge v1 v2)
                       v2))]
    (when (some identity vs)
      (reduce #(rec-merge %1 %2) v vs))))
