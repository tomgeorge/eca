(ns eca.shared
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string])
  (:import
   [java.net URI]
   [java.nio.file FileSystems Paths]))

(defn uri->filename [uri]
  (let [uri (URI. uri)]
    (-> uri Paths/get .toString
        ;; WINDOWS drive letters
        (string/replace #"^[a-z]:\\" string/upper-case))))

(defn any-path-matches? [filename root-filename patterns]
  (some
   (fn [pattern]
     (let [rel-path (fs/relativize root-filename filename)]
       (if (string/starts-with? pattern "glob:")
         ;; glob
         (or (.matches (.getPathMatcher (FileSystems/getDefault) pattern)
                       (fs/relativize root-filename filename))
             (string/starts-with? (str rel-path)
                                  (string/replace-first pattern "glob:" ""))
             (string/starts-with? (str (System/getProperty "file.separator") rel-path)
                                  (string/replace-first pattern "glob:" "")))
         ;; regex
         (re-matches (re-pattern (string/replace-first pattern "regex:" ""))
                     (str rel-path)))))
   patterns))
