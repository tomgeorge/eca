(ns eca.features.tools.filesystem
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.features.tools.util :as tools.util]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private allowed-path? [db path]
  (some #(fs/starts-with? path (shared/uri->filename (:uri %)))
        (:workspace-folders db)))

(defn ^:private path-validations [db]
  [["path" fs/exists? "$path is not a valid path"]
   ["path" (partial allowed-path? db) (str "Access denied - path $path outside allowed directories: " (tools.util/workspace-roots-strs db))]])

(defn ^:private list-directory [arguments {:keys [db]}]
  (let [path (delay (fs/canonicalize (get arguments "path")))]
    (or (tools.util/invalid-arguments arguments (path-validations db))
        (tools.util/single-text-content
         (reduce
          (fn [out path]
            (str out
                 (format "[%s] %s\n"
                         (if (fs/directory? path) "DIR" "FILE")
                         path)))
          ""
          (fs/list-dir @path))))))

(defn ^:private read-file [arguments {:keys [db]}]
  (or (tools.util/invalid-arguments arguments (concat (path-validations db)
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
        (tools.util/single-text-content content))))

(defn ^:private write-file [arguments {:keys [db]}]
  (or (tools.util/invalid-arguments arguments [["path" (partial allowed-path? db) (str "Access denied - path $path outside allowed directories: " (tools.util/workspace-roots-strs db))]])
      (let [path (get arguments "path")
            content (get arguments "content")]
        (fs/create-dirs (fs/parent (fs/path path)))
        (spit path content)
        (tools.util/single-text-content (format "Successfully wrote to %s" path)))))

(defn ^:private search-files [arguments {:keys [db]}]
  (or (tools.util/invalid-arguments arguments (concat (path-validations db)
                                                      [["pattern" #(not (string/blank? %)) "Invalid glob pattern '$pattern'"]]))
      (let [pattern (get arguments "pattern")
            pattern (if (string/includes? pattern "*")
                      pattern
                      (format "**%s**" pattern))
            paths (reduce
                   (fn [paths {:keys [uri]}]
                     (concat paths (fs/glob (shared/uri->filename uri)
                                            pattern)))
                   []
                   (:workspace-folders db))]
        (tools.util/single-text-content (if (seq paths)
                                          (string/join "\n" paths)
                                          "No matches found")))))

(defn ^:private run-ripgrep [path pattern include]
  (let [cmd (cond-> ["rg" "--files-with-matches" "--no-heading"]
              include (concat ["--glob" include])
              :always (concat ["-e" pattern path]))]
    (->> (apply shell/sh cmd)
         :out
         (string/split-lines)
         (filterv #(not (string/blank? %))))))

(defn ^:private run-grep [path pattern ^String include]
  (let [include-patterns (if (and include (.contains include "{"))
                           (let [pattern-match (re-find #"\*\.\{(.+)\}" include)]
                             (when pattern-match
                               (map #(str "*." %) (clojure.string/split (second pattern-match) #","))))
                           [include])
        cmd (cond-> ["grep" "-E" "-l" "-r" "--exclude-dir=.*"]
              (and include (> (count include-patterns) 1)) (concat (mapv #(str "--include=" %) include-patterns))
              include (concat [(str "--include=" include)])
              :always (concat [pattern path]))]
    (->> (apply shell/sh cmd)
         :out
         (string/split-lines)
         (filterv #(not (string/blank? %))))))

(defn ^:private run-java-grep [path pattern include]
  (let [include-pattern (when include
                          (re-pattern (str ".*\\.("
                                           (-> include
                                               (string/replace #"^\*\." "")
                                               (string/replace #"\*\.\{(.+)\}" "$1")
                                               (string/replace #"," "|"))
                                           ")$")))
        pattern-regex (re-pattern pattern)]
    (letfn [(search [dir]
              (keep
               (fn [file]
                 (cond
                   (and (fs/directory? file) (not (fs/hidden? file)))
                   (search file)

                   (and (not (fs/directory? file))
                        (or (nil? include-pattern)
                            (re-matches include-pattern (fs/file-name file))))
                   (try
                     (with-open [rdr (io/reader (fs/file file))]
                       (loop [lines (line-seq rdr)]
                         (when (seq lines)
                           (if (re-find pattern-regex (first lines))
                             (str (fs/canonicalize file))
                             (recur (rest lines))))))
                     (catch Exception _ nil))))
               (fs/list-dir dir)))]
      (when (fs/exists? path)
        (flatten (search path))))))

(defn ^:private grep
  "Searches for files containing patterns using regular expressions.

   This function provides a fast content search across files using three different
   backends depending on what's available:
   1. ripgrep (rg) - fastest, preferred when available
   2. grep - standard Unix tool fallback
   3. Pure Java implementation - slow, but cross-platform fallback

   Returns matching file paths, prioritizing by modification time when possible.
   Validates that the search path is within allowed workspace directories."
  [arguments {:keys [db]}]
  (or (tools.util/invalid-arguments arguments (concat (path-validations db)
                                                      [["path" fs/readable? "File $path is not readable"]
                                                       ["pattern" #(and % (not (string/blank? %))) "Invalid content regex pattern '$pattern'"]
                                                       ["include" #(or (nil? %) (not (string/blank? %))) "Invalid file pattern '$include'"]
                                                       ["max_results" #(or (nil? %) number?) "Invalid number '$max_results'"]]))
      (let [path (get arguments "path")
            pattern (get arguments "pattern")
            include (get arguments "include")
            max-results (or (get arguments "max_results") 1000)
            paths
            (->> (cond
                   (tools.util/command-available? "rg" "--version")
                   (run-ripgrep path pattern include)

                   (tools.util/command-available? "grep" "--version")
                   (run-grep path pattern include)

                   :else
                   (run-java-grep path pattern include))
                 (take max-results))]
        ;; TODO sort by modification time.
        (tools.util/single-text-content (if (seq paths)
                                          (string/join "\n" paths)
                                          "No files found for given pattern")))))

(defn ^:private replace-in-file [arguments {:keys [db]}]
  (or (tools.util/invalid-arguments arguments (concat (path-validations db)
                                                      [["path" fs/readable? "File $path is not readable"]]))
      (let [path (get arguments "path")
            original-content (get arguments "original_content")
            new-content (get arguments "new_content")
            all? (boolean (get arguments "all_occurrences"))
            content (slurp path)]
        (tools.util/single-text-content
         (if (string/includes? content original-content)
           (let [content (if all?
                           (string/replace content original-content new-content)
                           (string/replace-first content original-content new-content))]
             (spit path content)
             (format "Successfully replaced content in %s." path))
           (format "Original content not found in %s" path))))))

(defn ^:private move-file [arguments {:keys [db]}]
  (let [workspace-dirs (tools.util/workspace-roots-strs db)]
    (or (tools.util/invalid-arguments arguments [["source" fs/exists? "$source is not a valid path"]
                                                 ["source" (partial allowed-path? db) (str "Access denied - path $source outside allowed directories: " workspace-dirs)]
                                                 ["destination" (partial allowed-path? db) (str "Access denied - path $destination outside allowed directories: " workspace-dirs)]
                                                 ["destination" (complement fs/exists?) "Path $destination already exists"]])
        (let [source (get arguments "source")
              destination (get arguments "destination")]
          (fs/move source destination {:replace-existing false})
          (tools.util/single-text-content (format "Successfully moved %s to %s" source destination))))))

(def definitions
  {"eca_list_directory"
   {:description (str "Get a detailed listing of all files and directories in a specified path. "
                      "Results clearly distinguish between files and directories with [FILE] and [DIR] "
                      "prefixes. This tool is essential for understanding directory structure and "
                      "finding specific files within a directory."
                      "**Only works within the directories: $workspaceRoots.**")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the directory to list."}}
                 :required ["path"]}
    :handler #'list-directory}
   "eca_read_file"
   {:description (str "Read the complete contents of a file from the file system. "
                      "Handles various text encodings and provides detailed error messages "
                      "if the file cannot be read. Use this tool when you need to examine "
                      "the contents of a single file. Use the 'head' parameter to read only "
                      "the first N lines of a file, or the 'tail' parameter to read only "
                      "the last N lines of a file."
                      "**Only works within the directories: $workspaceRoots.**")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the file to read."}
                              "head" {:type "integer"
                                      :description "If provided, returns only the first N lines of the file"}
                              "tail" {:type "integer"
                                      :description "If provided, returns only the last N lines of the file"}}
                 :required ["path"]}
    :handler #'read-file}
   "eca_write_file"
   {:description (str "Create a new file or completely overwrite an existing file with new content. " +
                      "Use with caution as it will overwrite existing files without warning. " +
                      "Handles text content with proper encoding. "
                      "**Only works within the directories: $workspaceRoots.**")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the new file"}
                              "content" {:type "string"
                                         :description "The content of the new file"}}
                 :required ["path" "content"]}
    :handler #'write-file}
   "eca_move_file"
   {:description (str "Move or rename files and directories. Can move files between directories "
                      "and rename them in a single operation. If the destination exists, the "
                      "operation will fail. Works across different directories and can be used "
                      "for simple renaming within the same directory. "
                      "Both source and destination must be within the directories: $workspaceRoots.")
    :parameters  {:type "object"
                  :properties {"source" {:type "string"
                                         :description "The absolute origin file path to move."}
                               "destination" {:type "string"
                                              :description "The new absolute file path to move to."}}
                  :required ["source" "destination"]}
    :handler #'move-file}
   "eca_search_files"
   {:description (str "Recursively search for files and directories matching a pattern. "
                      "Searches through all subdirectories from the starting path. The search "
                      "is case-insensitive and matches partial names following java's FileSystem#getPathMatcher. Returns full paths to all "
                      "matching items. Great for finding files when you don't know their exact location. "
                      "**Only works within the directories: $workspaceRoots.**")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to start searching files from there."}
                              "pattern" {:type "string"
                                         :description (str "Glob pattern following java FileSystem#getPathMatcher matching files or directory names."
                                                           "Use '**' to match search in multiple levels like '**.txt'")}}
                 :required ["path" "pattern"]}
    :handler #'search-files}
   "eca_grep"
   {:description (str "Fast content search tool that works with any codebase size. "
                      "Finds the paths to files that have matching contents using regular expressions. "
                      "Supports full regex syntax (eg. \"log.*Error\", \"function\\s+\\w+\", etc.). "
                      "Filter files by pattern with the include parameter (eg. \"*.js\", \"*.{ts,tsx}\"). "
                      "Returns matching file paths sorted by modification time. "
                      "Use this tool when you need to find files containing specific patterns.")
    :parameters  {:type "object"
                  :properties {"path" {:type "string"
                                       :description "The absolute path to search in."}
                               "pattern" {:type "string"
                                          :description "The regular expression pattern to search for in file contents"}
                               "include" {:type "string"
                                          :description "File pattern to include in the search (e.g. \"*.clj\", \"*.{clj,cljs}\")"}
                               "max_results" {:type "integer"
                                              :description "Maximum number of results to return (default: 1000)"}}
                  :required ["path" "pattern"]}
    :handler #'grep}
   "eca_replace_in_file"
   {:description (str "Replace a specific string or content block in a file with new content. "
                      "Finds the exact original content and replaces it with new content. "
                      "Be extra careful to format the original-content exactly correctly, "
                      "taking extra care with whitespace and newlines. In addition to replacing strings, "
                      "this can also be used to prepend, append, or delete contents from a file.")
    :parameters  {:type "object"
                  :properties {"path" {:type "string"
                                       :description "The absolute file path to do the replace."}
                               "original_content" {:type "string"
                                                   :description "The exact content to find and replace"}
                               "new_content" {:type "string"
                                              :description "The new content to replace the original content with"}
                               "all_occurrences" {:type "boolean"
                                                  :description "Whether to replace all occurences of the file or just the first one (default)"}}
                  :required ["path" "original_content" "new_content"]}
    :handler #'replace-in-file}})
