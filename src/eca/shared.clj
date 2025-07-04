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
