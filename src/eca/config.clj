(ns eca.config
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]))

(defn ^:private eca-version* []
  (string/trim (slurp (io/resource "ECA_VERSION"))))

(def eca-version (memoize eca-version*))
