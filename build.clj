(ns build
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.build.api :as b]))

(def lib 'dev.ericdallo/eca)
(def current-version (string/trim (slurp (io/resource "ECA_VERSION"))))
(def class-dir "target/classes")
(def basis {:project "deps.edn"})
(def file "target/eca.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn ^:private standalone-aot-jar [opts]
  (clean opts)
  (println "Building uberjar...")
  (let [basis (b/create-basis (update basis :aliases concat (:extra-aliases opts)))
        src-dirs (into ["src" "resources"] (:extra-dirs opts))]
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :src-dirs src-dirs
                    :java-opts ["-server"]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file file
             :main 'eca.main
             :basis basis})))

(defn ^:private bin
  "Create an executable `eca` script out of UBER-FILE jar with
  the given OPTS.

  OPTS can be a map of
  :jvm-opts A vector of options ot pass to the JVM."
  [opts]
  (println "Generating bin...")
  (let [jvm-opts (concat (:jvm-opts opts []) ["-server"])]
    ((requiring-resolve 'deps-bin.impl.bin/build-bin)
     {:jar file
      :name "eca"
      :jvm-opts jvm-opts
      :skip-realign true})))

(defn debug-cli [opts]
  (standalone-aot-jar (merge opts {:extra-aliases [:dev :test]
                                   :extra-dirs ["dev"]}))
  (bin {:jvm-opts ["-XX:-OmitStackTraceInFastThrow"
                   "-Djdk.attach.allowAttachSelf=true"
                   "-Dclojure.core.async.go-checking=true"]}))
