(ns eca.features.index
  (:require
   [clojure.java.shell :as shell]))

(defn ignore? [filename root-filename config]
  (boolean
   (some
    (fn [{:keys [type]}]
      (case type
        :gitignore (= 0 (:exit
                         (try (shell/sh "git" "check-ignore" filename "--quiet"
                                        :dir root-filename)
                              (catch Exception _ nil))))
        nil))
    (get-in config [:index :ignore-files]))))
