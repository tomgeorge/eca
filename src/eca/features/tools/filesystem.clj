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

(defn ^:private allowed-path? [db path]
  (some #(fs/starts-with? path (shared/uri->filename (:uri %)))
        (:workspace-folders db)))

(defn ^:private invalid-arguments [arguments validator]
  (first (keep (fn [[key pred error-msg]]
                 (let [value (get arguments key)]
                   (when-not (pred value)
                     (single-text-content (string/replace error-msg (str "$" key) value) :error))))
               validator)))

(defn ^:private path-validations [db]
  [["path" fs/exists? "$path is not a valid path"]
   ["path" (partial allowed-path? db) "Access denied - path $path outside allowed directories"]])

(defn ^:private list-allowed-directories [_arguments db]
  (single-text-content
   (str "Allowed directories:\n"
        (string/join "\n"
                     (map (comp shared/uri->filename :uri) (:workspace-folders db))))))

(defn ^:private list-directory [arguments db]
  (let [path (delay (fs/canonicalize (get arguments "path")))]
    (or (invalid-arguments arguments (path-validations db))
        (single-text-content
         (reduce
          (fn [out path]
            (str out
                 (format "[%s] %s\n"
                         (if (fs/directory? path) "DIR" "FILE")
                         path)))
          ""
          (fs/list-dir @path))))))

(defn ^:private read-file [arguments db]
  (or (invalid-arguments arguments (concat (path-validations db)
                                           [["path" fs/readable? "File $path is not readable"]]))
      (let [head (get arguments "head")
            tail (get arguments "tail")
            content (cond-> (slurp (fs/file (fs/canonicalize (get arguments "path"))))
                      head (->> (string/split-lines)
                                (take head)
                                (string/join "\n"))
                      tail (->> (string/split-lines)
                                (take-last tail)
                                (string/join "\n")))]
        (single-text-content content))))

(def definitions
  {"list_allowed_directories"
   {:description (str "Returns the list of directories that this server is allowed to access. "
                      "Use this to understand which directories are available before trying to access files.")
    :parameters {:type "object"
                 :properties {}
                 :required []}
    :handler #'list-allowed-directories}
   "list_directory"
   {:description (str "Get a detailed listing of all files and directories in a specified path. "
                      "Results clearly distinguish between files and directories with [FILE] and [DIR] "
                      "prefixes. This tool is essential for understanding directory structure and "
                      "finding specific files within a directory. Only works within workspace root.")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the directory to list."}}
                 :required ["path"]}
    :handler #'list-directory}
   "read_file"
   {:description (str "Read the complete contents of a file from the file system. "
                      "Handles various text encodings and provides detailed error messages "
                      "if the file cannot be read. Use this tool when you need to examine "
                      "the contents of a single file. Use the 'head' parameter to read only "
                      "the first N lines of a file, or the 'tail' parameter to read only "
                      "the last N lines of a file. Only works within allowed directories.")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the file to read."}
                              "head" {:type "number"
                                      :description "If provided, returns only the first N lines of the file"}
                              "tail" {:type "number"
                                      :description "If provided, returns only the last N lines of the file"}}
                 :required ["path"]}
    :handler #'read-file}})
