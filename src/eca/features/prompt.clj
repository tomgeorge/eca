(ns eca.features.prompt
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.shared :refer [multi-str]]))

(defn ^:private eca-prompt-template* [] (slurp (io/resource "eca_prompt.txt")))

(def ^:private eca-prompt-template (memoize eca-prompt-template*))

(defn ^:private eca-prompt [behavior]
  (let [prompt (eca-prompt-template)]
    (reduce
     (fn [p [k v]]
       (string/replace p (str "{" (name k) "}") v))
     prompt
     {:behavior (case behavior
                  "chat" "Answer questions, and provide explanations."
                  "agent" "You are an agent - please keep going until the user's query is completely resolved, before ending your turn and yielding back to the user. Only terminate your turn when you are sure that the problem is solved. Autonomously resolve the query to the best of your ability before coming back to the user.")})))

(defn build-instructions [refined-contexts rules repo-map* behavior]
  (multi-str
   (eca-prompt behavior)
   "<rules>"
   (reduce
    (fn [rule-str {:keys [name content]}]
      (str rule-str (format "<rule name=\"%s\">%s</rule>\n" name content)))
    ""
    rules)
   "</rules>"
   ""
   "<contexts>"
   (reduce
    (fn [context-str {:keys [type path content partial]}]
      (str context-str (case type
                         :file (if partial
                                 (format "<file partial=true path=\"%s\">...\n%s\n...</file>\n" path content)
                                 (format "<file path=\"%s\">%s</file>\n" path content))
                         :repoMap (format "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >%s</repoMap>" @repo-map*)
                         "")))
    ""
    refined-contexts)
   "</contexts>"))
