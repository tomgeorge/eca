(ns eca.features.tools
  "This ns centralizes all available tools for LLMs including
   eca built-in tools and MCP servers."
  (:require
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.mcp :as f.mcp])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(defn built-in-definitions [config]
  (merge {}
         (when (get-in config [:builtInTools :filesystem :enabled])
           f.tools.filesystem/definitions)))

(defn all-tools [db config]
  (let [built-in-tools (concat
                        []
                        (mapv #(select-keys % [:name :description :parameters])
                              (vals (built-in-definitions config))))
        mcp-tools (f.mcp/all-tools db)]
    (concat
     (mapv #(assoc % :source :built-in) built-in-tools)
     (mapv #(assoc % :source :mcp) mcp-tools))))

(defn call-tool! [^String name ^Map arguments db config]
  (if-let [built-in-tool-handler (get-in (built-in-definitions config) [name :handler])]
    (built-in-tool-handler arguments db)
    (f.mcp/call-tool! name arguments db)))
