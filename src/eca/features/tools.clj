(ns eca.features.tools
  "This ns centralizes all available tools for LLMs including
   eca native tools and MCP servers."
  (:require
   [clojure.string :as string]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS]")

(defn native-definitions [db config]
  (into
   {}
   (map (fn [[name tool]]
          [name (-> tool
                    (assoc :name name)
                    (update :description #(-> %
                                              (string/replace #"\$workspaceRoots" (constantly (tools.util/workspace-roots-strs db))))))]))
   (merge {}
          (when (get-in config [:nativeTools :filesystem :enabled])
            f.tools.filesystem/definitions)
          (when (get-in config [:nativeTools :shell :enabled])
            f.tools.shell/definitions))))

(defn all-tools
  "Returns all available tools, including both native ECA tools
   (like filesystem and shell tools) and tools provided by MCP servers."
  [db config]
  (let [native-tools (concat
                      []
                      (mapv #(select-keys % [:name :description :parameters])
                            (vals (native-definitions db config))))
        mcp-tools (f.mcp/all-tools db)]
    (concat
     (mapv #(assoc % :source :native) native-tools)
     (mapv #(assoc % :source :mcp) mcp-tools))))

(defn call-tool! [^String name ^Map arguments db config]
  (logger/info logger-tag (format "Calling tool '%s' with args '%s'" name arguments))
  (if-let [native-tool-handler (get-in (native-definitions db config) [name :handler])]
    (native-tool-handler arguments {:db db :config config})
    (f.mcp/call-tool! name arguments db)))
