(ns eca.features.index
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(defn filter-allowed [file-paths root-filename config]
  (reduce
   (fn [files {:keys [type]}]
     (case type
       :gitignore (let [git-files (try (some->> (some-> (shell/sh "git" "ls-files"
                                                                  :dir root-filename)
                                                        :out
                                                        (string/split #"\n"))
                                                (mapv (comp str fs/canonicalize #(fs/file root-filename %)))
                                                set)
                                       (catch Exception _ nil))]
                    (println git-files (str (last files)))
                    (if (seq git-files)
                      (filter (fn [file]
                                (contains? git-files (str file)))
                              files)
                      files))
       files))
   file-paths
   (get-in config [:index :ignoreFiles])))
