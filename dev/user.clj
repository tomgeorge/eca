(ns user
  (:require
   [eca.db :as db]))

(alter-var-root #'*warn-on-reflection* (constantly true))

(def ^:dynamic *db* @db/db*)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defmacro with-workspace-root [root-uri & body]
  `(binding [*db* (assoc *db* :workspace-folders [{:name "dev" :uri ~root-uri}])]
     ~@body))
