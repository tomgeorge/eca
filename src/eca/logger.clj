(ns eca.logger)

(set! *warn-on-reflection* true)

(def ^:dynamic *level* :info)

(def ^:private level->value
  {:error 1
   :warn 2
   :info 3
   :debug 4})

(defn ^:private stderr-print [level & args]
  (when (<= (level->value level) (level->value *level*))
    (binding [*out* *err*]
      (apply println args))))

(defn error [& args]
  (apply stderr-print :error args))

(defn warn [& args]
  (apply stderr-print :warn args))

(defn info [& args]
  (apply stderr-print :info args))

(defn debug [& args]
  (apply stderr-print :debug args))

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
