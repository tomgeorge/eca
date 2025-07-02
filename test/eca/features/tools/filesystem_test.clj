(ns eca.features.tools.filesystem-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(deftest list-allowed-directories-test
  (is (match?
       {:contents [{:type :text
                    :error false
                    :content (format "Allowed directories:\n%s"
                                     (h/file-path "/foo/bar/baz"))}]}
       ((get-in f.tools.filesystem/definitions ["list_allowed_directories" :handler])
        {}
        {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "baz"}]}))))

(deftest list-directory-test
  (testing "unallowed dir"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content "Access denied - path outside workspace root, call list_allowed_dirs first"}]}
         ((get-in f.tools.filesystem/definitions ["list_directory" :handler])
          {"path" (h/file-path "/foo/qux")}
          {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "baz"}]}))))
  (testing "allowed dir"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content (format (str "[FILE] %s\n"
                                            "[DIR] %s\n")
                                       (h/file-path "/foo/bar/baz/some.clj")
                                       (h/file-path "/foo/bar/baz/qux"))}]}
         (with-redefs [fs/starts-with? (constantly true)
                       fs/list-dir (constantly [(fs/path (h/file-path "/foo/bar/baz/some.clj"))
                                                (fs/path (h/file-path "/foo/bar/baz/qux"))])
                       fs/directory? (fn [path] (not (string/ends-with? (str path) ".clj")))]
           ((get-in f.tools.filesystem/definitions ["list_directory" :handler])
            {"path" (h/file-path "/foo/bar/baz")}
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "baz"}]}))))))
