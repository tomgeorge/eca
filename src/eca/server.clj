(ns eca.server
  (:require
   [clojure.core.async :as async]
   [eca.db :as db]
   [eca.handlers :as handlers]
   [eca.logger :as logger]
   [eca.nrepl :as nrepl]
   [lsp4clj.io-server :as io-server]
   [lsp4clj.liveness-probe :as liveness-probe]
   [lsp4clj.server :as lsp.server]))

(def ^:private logger-tag "[server]")

(defn ^:private log-wrapper-fn
  [_level & args]
  (apply logger/info args))

(defn ^:private exit [server]
  (logger/logging-task
   :eca/exit
   (lsp.server/shutdown server) ;; blocks, waiting up to 10s for previously received messages to be processed
   (shutdown-agents)
   (System/exit 0)))

(defmethod lsp.server/receive-request "initialize" [_ {:keys [server] :as components} params]
  (when-let [parent-process-id (:process-id params)]
    (liveness-probe/start! parent-process-id log-wrapper-fn #(exit server)))
  (handlers/initialize components params))

(defmethod lsp.server/receive-notification "initialized" [_ _components _params]
  (logger/info logger-tag "Initialized!"))

(defmethod lsp.server/receive-request "shutdown" [_ components _params]
  (handlers/shutdown components))

(defmethod lsp.server/receive-notification "exit" [_ {:keys [server]} _params]
  (exit server))

(defn ^:private monitor-server-logs [log-ch]
  ;; NOTE: if this were moved to `initialize`, after timbre has been configured,
  ;; the server's startup logs and traces would appear in the regular log file
  ;; instead of the temp log file. We don't do this though because if anything
  ;; bad happened before `initialize`, we wouldn't get any logs.
  (async/go-loop []
    (when-let [log-args (async/<! log-ch)]
      (apply log-wrapper-fn log-args)
      (recur))))

(defn ^:private setup-dev-environment [db* components]
  ;; We don't have an ENV=development flag, so the next best indication that
  ;; we're in a development environment is whether we're able to start an nREPL.
  (when-let [nrepl-port (nrepl/setup-nrepl)]
    ;; Save the port in the db, so it can be reported in server-info.
    (swap! db* assoc :port nrepl-port)
    ;; Add components to db* so it's possible to manualy call funcstions
    ;; which expect specific components
    (swap! db* assoc-in [:dev :components] components)))

(defn start-server! [server]
  (let [db* (atom db/initial-db)
        components {:db* db*
                    :server server}]
    (logger/info "[server]" "Starting server...")
    (monitor-server-logs (:log-ch server))
    (setup-dev-environment db* components)
    (lsp.server/start server components)))

(defn run-io-server! [verbose?]
  (lsp.server/discarding-stdout
   (let [log-ch (async/chan (async/sliding-buffer 20))
         server (io-server/stdio-server {:log-ch log-ch
                                         :trace-ch log-ch
                                         :trace-level (if verbose? "verbose" "off")})]
     (start-server! server))))
