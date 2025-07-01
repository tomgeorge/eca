(ns eca.features.tools.filesystem
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private single-text-content [text & [error]]
  {:contents [{:type :text
               :content text
               :error (boolean error)}]})

(defn ^:private not-allowed-path [path db]
  (when-not (some #(fs/starts-with? path (shared/uri->filename (:uri %)))
                  (:workspace-folders db))
    (single-text-content "Access denied - path outside workspace root" :error)))

(defn ^:private list-allowed-directories [_arguments db]
  (single-text-content
   (str "Allowed directories:\n"
        (string/join "\n"
                     (map (comp shared/uri->filename :uri) (:workspace-folders db))))))

(defn ^:private list-directory [arguments db]
  (let [path (get arguments "path")]
    (or (not-allowed-path path db)
        (single-text-content
         (reduce
          (fn [out path]
            (str out
                 (format "[%s] %s\n"
                         (if (fs/directory? path) "DIR" "FILE")
                         path)))
          ""
          (fs/list-dir path))))))

(def definitions
  {"list_allowed_directories"
   {:name "list_allowed_directories"
    :description (str "Returns the list of directories that this server is allowed to access."
                      "Use this to understand which directories are available before trying to access files.")
    :parameters {:type "object"
                 :properties {}
                 :required []}
    :handler #'list-allowed-directories}
   "list_directory"
   {:name "list_directory"
    :description (str "Get a detailed listing of all files and directories in a specified path. "
                      "Results clearly distinguish between files and directories with [FILE] and [DIR] "
                      "prefixes. This tool is essential for understanding directory structure and "
                      "finding specific files within a directory. Only works within workspace root.")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The path to the directory to list."}}
                 :required ["path"]}
    :handler #'list-directory}})
