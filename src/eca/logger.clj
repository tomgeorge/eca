(ns eca.logger)
;; TODO create better logger capability.

(defn info [& args]
  (binding [*out* *err*]
    (apply println args)))
