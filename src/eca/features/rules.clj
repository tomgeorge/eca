(ns eca.features.rules
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private global-file-rules []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))
        rules-dir (io/file xdg-config-home "eca" "rules")]
    (when (fs/exists? rules-dir)
      (map (fn [file]
             {:name (fs/file-name file)
              :path (str (fs/canonicalize file))
              :type :user-global-file
              :content (slurp (fs/file file))})
           (fs/list-dir rules-dir)))))

(defn ^:private local-file-rules [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [rules-dir (fs/file (shared/uri->filename uri) ".eca" "rules")]
                   (when (fs/exists? rules-dir)
                     (fs/list-dir rules-dir)))))
       (map (fn [file]
              {:name (fs/file-name file)
               :path (str (fs/canonicalize file))
               :type :user-local-file
               :content (slurp (fs/file file))}))))

(defn ^:private config-rules [config roots]
  (->> (get config :rules)
       (map
        (fn [{:keys [path]}]
          (if (fs/absolute? path)
            (when (fs/exists? path)
              {:name (fs/file-name path)
               :path path
               :type :user-config
               :content (slurp path)})
            (keep (fn [{:keys [uri]}]
                    (let [f (fs/file (shared/uri->filename uri) path)]
                      (when (fs/exists? f)
                        {:name (fs/file-name f)
                         :path (str (fs/canonicalize f))
                         :type :user-config
                         :content (slurp f)})))
                  roots))))
       (flatten)
       (remove nil?)))

(defn ^:private system-rules []
  [{:name "ECA System"
    :type :system
    :content (str "You are an expert AI coding tool called ECA (Editor Code Assistant). "
                  "Your behavior is to '<behavior>'. "
                  "The chat is markdown mode. "
                  "When responding code blocks, pay attention to use valid markdown languages following Github markdown.")}])

(defn all [config roots variables]
  (mapv (fn [rule]
          (reduce
           (fn [rule [k v]]
             (update rule :content #(string/replace % (str "<" (name k) ">") v)))
           rule
           variables))
        (concat (system-rules)
                (config-rules config roots)
                (global-file-rules)
                (local-file-rules roots))))
