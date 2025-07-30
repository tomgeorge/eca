(ns eca.features.tools.shell
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS-SHELL]")

(defn ^:private shell-command [arguments {:keys [db config]}]
  (let [command (get arguments "command")
        user-work-dir (get arguments "working_directory")
        exclude-cmds (-> config :nativeTools :shell :excludeCommands set)
        command-args (string/split command #" ")]
    (or (tools.util/invalid-arguments arguments [["working_directory" #(or (nil? %)
                                                                           (fs/exists? %)) "working directory $working_directory does not exist"]
                                                 ["commmand" (constantly (not (contains? exclude-cmds (first command-args)))) (format "Cannot run command '%s' because it is excluded by eca config."
                                                                                                                                      (first command-args))]])
        (let [work-dir (or (some-> user-work-dir fs/canonicalize str)
                           (some-> (:workspace-folders db)
                                   first
                                   :uri
                                   shared/uri->filename)
                           (config/get-property "user.home"))
              command-and-opts (concat [] command-args [:dir work-dir])
              _ (logger/debug logger-tag "Running command:" command-and-opts)
              result (try
                       (apply shell/sh command-and-opts)
                       (catch Exception e
                         {:exit 1 :err (.getMessage e)}))
              err (some-> (:err result) string/trim)
              out (some-> (:out result) string/trim)]
          (logger/debug logger-tag "Command executed:" result)
          (if (zero? (:exit result))
            (tools.util/single-text-content (:out result))
            {:error true
             :contents (remove nil?
                               (concat [{:type :text
                                         :content (str "Exit code " (:exit result))}]
                                       (when-not (string/blank? err)
                                         [{:type :text
                                           :content (str "Stderr:\n" err)}])
                                       (when-not (string/blank? out)
                                         [{:type :text
                                           :content (str "Stdout:\n" out)}])))})))))

(def definitions
  {"eca_shell_command"
   {:description (str "Execute an arbitrary shell command and return the output. "
                      "Useful to run commands like `ls`, `git status`, etc.")
    :parameters {:type "object"
                 :properties {"command" {:type "string"
                                         :description "The shell command to execute."}
                              "working_directory" {:type "string"
                                                   :description "The directory to run the command in. Default to the first workspace root."}}
                 :required ["command"]}
    :handler #'shell-command}})
