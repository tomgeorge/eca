(ns make
  (:require
   [babashka.deps :as deps]
   [babashka.fs :as fs]
   [babashka.process :as p]))

(def windows? (#'fs/windows?))

(defn ^:private mv-here [file]
  (fs/move file "." {:replace-existing true}))

(defn ^:private clj! [cmd]
  (-> (deps/clojure cmd {:inherit true})
      (p/check)))

(defn ^:private build [tool] (clj! ["-T:build" tool]))

(defn eca-bin-filename
  [usage]
  (cond-> "eca"
    windows? (str (case usage
                    :native ".exe"
                    :script ".bat"))))

(defn debug-cli
  "Build the `eca[.bat]` debug executable (suppots `cider-nrepl`)."
  []
  (build "debug-cli")
  (mv-here (fs/path (eca-bin-filename :script))))

(defn prod-jar []
  (build "prod-jar")
  (mv-here "target/eca.jar"))

(defn native-cli
  "Build the native `eca[.exe]` cli executable with `graalvm`."
  []
  (build "native-cli")
  (mv-here (fs/path (eca-bin-filename :native))))
