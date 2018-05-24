(ns apps.service.apps.de.validation
  (:use [apps.persistence.app-metadata :only [get-app
                                              get-app-tools
                                              list-duplicate-apps
                                              list-duplicate-apps-by-id
                                              parameter-types-for-tool-type]]
        [apps.persistence.entities :only [tools tool_types]]
        [clojure-commons.exception-util :only [forbidden exists]]
        [korma.core :exclude [update]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.permissions :as perms-client]
            [apps.service.apps.de.permissions :as perms]))

(defn validate-app-existence
  "Verifies that apps exist."
  [app-id]
  (get-app app-id))

(defn- task-ids-for-app
  "Get the list of task IDs associated with an app."
  [app-id]
  (map :task_id
       (select [:app_steps :step]
               (fields :step.task_id)
               (where {:step.app_id app-id}))))

(defn- private-apps-for
  "Finds private single-step apps for a list of task IDs."
  [task-ids public-app-ids]
  (select [:app_listing :a]
          (fields :a.id :a.name)
          (join [:app_steps :step]
                {:a.id :step.app_id})
          (where {:step.task_id [in task-ids]
                  :a.step_count 1
                  :a.id         [not-in public-app-ids]})))

(defn- list-unrunnable-tasks
  "Determines which of a collection of task IDs are not runnable."
  [task-ids]
  (map :id
       (select [:tasks :t]
               (fields :t.id)
               (where {:t.id              [in task-ids]
                       :t.tool_id         nil
                       :t.external_app_id nil}))))

(defn app-publishable?
  "Determines whether or not an app can be published. An app is publishable if none of the
   templates in the app are associated with any single-step apps that are not public. Returns
   a flag indicating whether or not the app is publishable along with the reason the app isn't
   publishable if it's not."
  [{username :shortUsername} app-id]
  (validate-app-existence app-id)
  (perms/check-app-permissions username "own" [app-id])
  (let [task-ids         (task-ids-for-app app-id)
        unrunnable-tasks (list-unrunnable-tasks task-ids)
        tools            (get-app-tools app-id)
        public-app-ids   (perms-client/get-public-app-ids)
        public-tool-ids  (perms-client/get-public-tool-ids)
        is-public?       (contains? public-app-ids app-id)
        private-tools    (remove #(contains? public-tool-ids (:id %)) tools)
        deprecated-tools (filter :deprecated tools)
        private-apps     (private-apps-for task-ids public-app-ids)]
    (cond is-public?             [false "app is already public"]
          (empty? task-ids)      [false "no app ID provided"]
          (seq unrunnable-tasks) [false "contains unrunnable tasks" unrunnable-tasks]
          (seq private-tools)    [false "contains private tools" private-tools]
          (seq deprecated-tools) [false "contains deprecated tools" deprecated-tools]
          (= 1 (count task-ids)) [true]
          (seq private-apps)     [false "contains private apps" private-apps]
          :else                  [true])))

(defn- verify-app-not-public
  "Verifies that an app has not been made public."
  [app]
  (when (contains? (perms-client/get-public-app-ids) (:id app))
    (throw+ {:type  :clojure-commons.exception/not-writeable
             :error (str "Workflow, " (:id app) ", is public and may not be edited")})))

(defn verify-app-permission
  "Verifies that the user has sufficient privileges for an app."
  ([user app level]
   (verify-app-permission user app level false))
  ([{user :shortUsername} {app-id :id} level admin?]
    ;; FIXME: find better permission checks for admin users
   (when-not admin?
     (perms/check-app-permissions user level [app-id]))))

(defn verify-app-editable
  "Verifies that the app is allowed to be edited by the current user."
  [user app]
  (verify-app-permission user app "write")
  (verify-app-not-public app))

(def ^:private duplicate-app-selected-categories-msg
  "An app with the same name already exists in one of the selected categories.")

(def ^:private duplicate-app-existing-categories-msg
  "An app with the same name already exists in one of the same categories.")

(defn validate-app-name
  "Verifies that an app with the same name doesn't already exist in any of the same app categories."
  ([app-name app-id]
   (validate-app-name app-name app-id nil))
  ([app-name app-id category-ids]
   (when (seq (list-duplicate-apps app-name app-id category-ids))
     (if (seq category-ids)
       (exists duplicate-app-selected-categories-msg :app_name app-name :category_ids category-ids)
       (exists duplicate-app-existing-categories-msg :app_name app-name :app_id app-id))))
  ([app-name app-id category-ids path]
   (when (seq (list-duplicate-apps app-name app-id category-ids))
     (if (seq category-ids)
       (exists duplicate-app-selected-categories-msg :app_name app-name :category_ids category-ids :path path)
       (exists duplicate-app-existing-categories-msg :app_name app-name :app_id app-id :path path)))))

(defn validate-app-name-in-hierarchy
  [app-name app-ids]
  (let [duplicate-apps (list-duplicate-apps-by-id app-name app-ids)]
    (when-not (empty? duplicate-apps)
      (exists duplicate-app-existing-categories-msg :app_name app-name :apps duplicate-apps))))
