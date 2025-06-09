(ns eca.logger)

;; TODO create better logger capability.
(defn info [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn format-time-delta-ms [start-time end-time]
  (format "%.0fms" (float (/ (- end-time start-time) 1000000))))

(defn start-time->end-time-ms [start-time]
  (format-time-delta-ms start-time (System/nanoTime)))

(defmacro logging-time
  "Executes `body` logging `message` formatted with the time spent
  from body."
  [message & body]
  (let [start-sym (gensym "start-time")]
    `(let [~start-sym (System/nanoTime)
           result# (do ~@body)]
       ~(with-meta
          `(info (format ~message (start-time->end-time-ms ~start-sym)))
          (meta &form))
       result#)))

(defmacro logging-task [task-id & body]
  (with-meta `(logging-time (str ~task-id " %s") ~@body)
    (meta &form)))
