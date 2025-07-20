(ns eca.features.index-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [eca.features.index :refer [filter-allowed]]
   [matcher-combinators.test :refer [match?]]))

(def gitignore-config
  {:index {:ignoreFiles [{:type :gitignore}]}})

(deftest ignore?-test
  (testing "gitignore type"
    (let [root  "/fake/repo"
          file1 (fs/path root "ignored.txt")
          file2 (fs/path root "not-ignored.txt")]
      (testing "returns filtered files when `git ls-files` works"
        (with-redefs [shell/sh (fn [& _args] {:exit 0 :out "not-ignored.txt"})
                      fs/canonicalize #(fs/path root %)]
          (is
           (match?
            [file2]
            (filter-allowed [file1 file2] root gitignore-config)))))

      (testing "returns all files when `git ls-files` exits non-zero"
        (with-redefs [shell/sh (fn [& _args] {:exit 1})]
          (is
           (match?
            [file1 file2]
            (filter-allowed [file1 file2] root gitignore-config))))))))
