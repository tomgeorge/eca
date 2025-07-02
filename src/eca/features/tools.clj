(ns eca.features.tools
  "This ns centralizes all available tools for LLMs including
   eca native tools and MCP servers."
  (:require
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.mcp :as f.mcp]
   [eca.logger :as logger])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS]")

(defn native-definitions [config]
  (merge {}
         (when (get-in config [:nativeTools :filesystem :enabled])
           f.tools.filesystem/definitions)))

(defn all-tools [db config]
  (let [native-tools (concat
                      []
                      (mapv #(select-keys % [:name :description :parameters])
                            (vals (native-definitions config))))
        mcp-tools (f.mcp/all-tools db)]
    (concat
     (mapv #(assoc % :source :native) native-tools)
     (mapv #(assoc % :source :mcp) mcp-tools))))

(defn call-tool! [^String name ^Map arguments db config]
  (logger/debug logger-tag (format "Calling tool '%s' with args '%s'" name arguments))
  (if-let [native-tool-handler (get-in (native-definitions config) [name :handler])]
    (native-tool-handler arguments db)
    (f.mcp/call-tool! name arguments db)))
