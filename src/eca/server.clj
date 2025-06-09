(ns eca.server
  (:require
   [clojure.core.async :as async]
   [eca.db :as db]
   [eca.logger :as logger]
   [eca.nrepl :as nrepl]
   [lsp4clj.io-server :as lsp.io-server]
   [lsp4clj.server :as lsp.server]))

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
    ;; (monitor-server-logs (:log-ch server))
    (setup-dev-environment db* components)
    (lsp.server/start server components)))

(defn run-io-server! [verbose?]
  (lsp.server/discarding-stdout
   (let [log-ch (async/chan (async/sliding-buffer 20))
         server (lsp.io-server/stdio-server {:log-ch log-ch
                                             :trace-ch log-ch
                                             :trace-level (if verbose? "verbose" "off")})]
     (start-server! server))))
