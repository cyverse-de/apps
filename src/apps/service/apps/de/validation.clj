(ns apps.service.apps.de.validation
  (:use [apps.persistence.app-metadata :only [get-app
                                              get-app-tools
                                              get-app-version-tools
                                              get-app-version
                                              list-duplicate-apps
                                              list-duplicate-apps-by-id
                                              parameter-types-for-tool-type
                                              task-ids-for-app-version]]
        [apps.persistence.entities :only [tools tool_types]]
        [clojure-commons.exception-util :only [forbidden exists]]
        [korma.core :exclude [update]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.permissions :as perms-client]
            [apps.service.apps.de.permissions :as perms]
            [apps.util.config :as config]
            [clojure-commons.exception-util :as ex-util]
            [clojure.string :as string]))

(defn validate-app-existence
  "Verifies that apps exist."
  [app-id]
  (get-app app-id))

(defn validate-app-version-existence
  "Verifies that a specific app version exists."
  [app-id app-version-id]
  (get-app-version app-id app-version-id))

(defn- app-version-ids
  "Get the list of version IDs associated with an app."
  [app-id]
  (map :id
       (select :app_versions
               (fields :id)
               (where {:app_id  app-id
                       :deleted false}))))

(defn- private-apps-for
  "Finds private single-step apps for a list of task IDs."
  [task-ids public-app-ids]
  (select [:app_listing :a]
          (fields :a.id :a.name)
          (join [:app_versions :v]
                {:a.id :v.app_id})
          (join [:app_steps :step]
                {:v.id :step.app_version_id})
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

(defn- remove-publishable-tools
  "A tool is publishable if the authenticated user has ownership of the tool."
  [username tools]
  (let [tool-ids  (mapv :id tools)
        owned-id? (comp (set (keys (perms-client/load-tool-permissions username tool-ids "own"))) :id)]
    (remove owned-id? tools)))

(defn- get-unpublishable-tools
  [username tools]
  (let [public-tool-ids (perms-client/get-public-tool-ids)]
    (->> (remove (comp public-tool-ids :id) tools)
         (remove-publishable-tools username))))

(defn app-tasks-and-tools-publishable?
  "Determines whether an app's tools and tasks can be published.
   Returns a flag indicating whether the app version is publishable
   along with the reason the app version isn't publishable if it's not."
  [username admin? public-app-ids task-ids tools]
  (let [unrunnable-tasks    (list-unrunnable-tasks task-ids)
        unpublishable-tools (when-not admin? (get-unpublishable-tools username tools))
        deprecated-tools    (filter :deprecated tools)
        private-apps        (private-apps-for task-ids public-app-ids)]
    (cond (empty? task-ids)         [false "no app version ID provided"]
          (seq unrunnable-tasks)    [false "contains unrunnable tasks" unrunnable-tasks]
          (seq unpublishable-tools) [false "contains unpublishable tools" unpublishable-tools]
          (seq deprecated-tools)    [false "contains deprecated tools" deprecated-tools]
          (= 1 (count task-ids))    [true]
          (seq private-apps)        [false "contains private apps" private-apps]
          :else                     [true])))

(defn- app-version-publishable?
  "Determines whether an app version can be published.
   Returns a flag indicating whether the app version is publishable
   along with the reason the app version isn't publishable if it's not."
  [username admin? public-app-ids app-id app-version-id]
  (let [task-ids            (task-ids-for-app-version app-version-id)
        tools               (get-app-version-tools app-id app-version-id)]
    (app-tasks-and-tools-publishable? username admin? public-app-ids task-ids tools)))

(defn app-publishable?
  "Determines whether or not an app can be published.
   An app is publishable if all versions of the app can be published.
   Returns a flag indicating whether or not the app is publishable
   along with the reason the app isn't publishable if it's not."
  [{username :shortUsername} app-id admin?]
  (validate-app-existence app-id)
  (when-not admin? (perms/check-app-permissions username "own" [app-id]))
  (let [version-ids           (app-version-ids app-id)
        public-app-ids        (perms-client/get-public-app-ids)
        is-public?            (contains? public-app-ids app-id)
        unpublishable-version (->> version-ids
                                   (map (partial app-version-publishable?
                                                 username
                                                 admin?
                                                 public-app-ids
                                                 app-id))
                                   (filter (comp not first))
                                   first)]
    (cond is-public?                  [false "app is already public"]
          (empty? version-ids)        [false "no app ID provided"]
          (seq unpublishable-version) unpublishable-version
          :else                       [true])))

(defn uses-tools-in-untrusted-registries?
  "Determines whether or not any of the tools used by an app are in an untrusted Docker repository."
  [app-id]
  (let [trusted-registries     (set (config/trusted-registries))
        public-tool-ids        (set (perms-client/get-public-tool-ids))
        private-tool?          #(not (contains? public-tool-ids (:id %)))
        get-registry           (comp first #(string/split % #"/" 2) :image_name)
        in-untrusted-registry? #(not (contains? trusted-registries (get-registry %)))]
    (some (every-pred in-untrusted-registry? private-tool?) (get-app-tools app-id))))

(defn verify-tools-for-public-app
  "Verifies that a public app will not use the given private or missing tools."
  [tools err-msg]
  (let [public-tool-ids (perms-client/get-public-tool-ids)
        private-tools   (remove (comp public-tool-ids :id) tools)]
    (when (or (empty? tools) (not-empty private-tools))
      (throw+ (ex-util/bad-request err-msg :tools private-tools)))))

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

(def protected-attrs
  #{(config/workspace-metadata-beta-attr-iri)
    (config/workspace-metadata-communities-attr)
    (config/workspace-metadata-certified-apps-attr)})

(def ^:private protected-attr-msg
  "Some of the selected attributes may only be modified by Discovery Environment administrators.")

(defn validate-attrs-not-protected
  [admin? avus]
  (when-not admin?
    (let [requested-attrs (set (map :attr avus))
          forbidden-attrs (set (remove nil? (map protected-attrs requested-attrs)))]
      (when-not (empty? forbidden-attrs)
        (forbidden protected-attr-msg :attrs (vec forbidden-attrs))))))
