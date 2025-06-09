(ns eca.handlers
  (:require
   [eca.db :as db]
   [eca.logger :as logger]))

(defn initialize [components params]
  (logger/logging-task
   :eca/initialize
   {}))

(defn shutdown [{:keys [db*]}]
  (logger/logging-task
   :eca/shutdown
   (reset! db* db/initial-db)
   nil))
