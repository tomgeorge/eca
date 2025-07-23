(ns eca.features.index
  (:require
   [babashka.fs :as fs]
   [clojure.core.memoize :as memoize]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private ttl-git-ls-files-ms 5000)

(defn ^:private git-ls-files* [root-path]
  (try
    (some-> (shell/sh "git" "ls-files" "--others" "--exclude-standard" "--cached"
                      :dir root-path)
            :out
            (string/split #"\n"))

    (catch Exception _ nil)))

(def ^:private git-ls-files (memoize/ttl git-ls-files* :ttl/threshold ttl-git-ls-files-ms))

(defn filter-allowed [file-paths root-filename config]
  (reduce
   (fn [files {:keys [type]}]
     (case type
       :gitignore (let [git-files (some->> (git-ls-files root-filename)
                                           (mapv (comp str fs/canonicalize #(fs/file root-filename %)))
                                           set)]
                    (if (seq git-files)
                      (filter (fn [file]
                                (contains? git-files (str file)))
                              files)
                      files))
       files))
   file-paths
   (get-in config [:index :ignoreFiles])))

(defn insert-path [tree parts]
  (if (empty? parts)
    tree
    (let [head (first parts)
          tail (rest parts)]
      (update tree head #(insert-path (or % {}) tail)))))

(defn tree->str
  ([tree] (tree->str tree 0))
  ([tree indent]
   (let [indent-str (fn [level] (apply str (repeat (* 1 level) " ")))]
     (apply str
            (mapcat (fn [[k v]]
                      (let [current-line (str (indent-str indent) k "\n")
                            children-str (when (seq v)
                                           (tree->str v (inc indent)))]
                        [current-line children-str]))
                    (sort tree))))))

(defn repo-map [db & {:keys [as-string?]}]
  (let [tree (reduce
              (fn [t {:keys [uri]}]
                (let [root-filename (shared/uri->filename uri)
                      files (git-ls-files root-filename)]
                  (merge t
                         {root-filename
                          (reduce
                           (fn [tree path]
                             (insert-path tree (clojure.string/split path #"/")))
                           {}
                           files)})))
              {}
              (:workspace-folders db))]
    (if as-string?
      (tree->str tree)
      tree)))

(comment
  (require 'user)
  (user/with-workspace-root "file:///home/greg/dev/eca"
    (println (repo-map user/*db* {:as-string? true}))))
