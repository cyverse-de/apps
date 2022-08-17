(ns apps.persistence.app-metadata.relabel
  "Persistence layer for app metadata."
  (:use [apps.persistence.entities]
        [apps.util.assertions]
        [apps.util.conversions :only [long->timestamp
                                      remove-nil-vals]]
        [korma.core :exclude [update]]
        [medley.core :only [remove-vals]]
        [slingshot.slingshot :only [throw+]])
  (:require [apps.persistence.app-metadata :as app-meta]
            [korma.core :as sql]))

(defn- get-tasks-for-app
  "Retrieves the list of tasks associated with an app."
  [app-id app-version-id]
  (select [:apps :a]
          (fields :t.id :t.external_app_id :t.name :t.description :t.label :t.tool_id)
          (join [:app_versions :v]
                {:a.id :v.app_id})
          (join [:app_steps :step]
                {:v.id :step.app_version_id})
          (join [:tasks :t]
                {:step.task_id :t.id})
          (where {:a.id app-id
                  :v.id app-version-id})))

(defn- get-single-task-for-app
  "Retrieves the task from a single-step app. An exception will be thrown if the app doesn't have
   exactly one step."
  [app-id app-version-id]
  (let [tasks (get-tasks-for-app app-id app-version-id)]
    (when (not= 1 (count tasks))
      (throw+ {:type       :clojure-commons.exception/illegal-argument
               :error      :NOT_SINGLE_STEP_APP
               :step_count (count tasks)}))
    (first tasks)))

(defn- get-parameter-group-in-task
  "Verifies that a selected parameter group belongs to a specific task."
  [task-id group-id]
  (assert-not-nil
   [:group_id group-id]
   (first
    (select [parameter_groups :pg]
            (join [:tasks :t]
                  {:pg.task_id :t.id})
            (where {:t.id  task-id
                    :pg.id group-id})))))

(defn- get-parameter-in-group
  "Verifies that a parameter belongs to a specific parameter group."
  [group-id parameter-id]
  (assert-not-nil
   [:parameter_id parameter-id]
   (first
    (select [parameters :p]
            (fields :p.id [:t.name :info_type])
            (join [:parameter_groups :pg]
                  {:p.parameter_group_id :pg.id})
            (join [:file_parameters :f]
                  {:f.parameter_id :p.id})
            (join [:info_type :t]
                  {:t.id :f.info_type})
            (where {:pg.id group-id
                    :p.id  parameter-id})))))

(defn- get-parameter-value
  "Verifies that a parameter value belongs to a specific parameter."
  [parameter-id param-value-id]
  (assert-not-nil
   [:parameter_value_id param-value-id]
   (first
    (select [:parameter_values :v]
            (join [:parameters :p]
                  {:p.id :v.parameter_id})
            (where {:p.id parameter-id
                    :v.id param-value-id})))))

(def ^:private generated-selection-list-info-types
  "The list of info types for which selection lists are generated."
  ["ReferenceAnnotation" "ReferenceGenome" "ReferenceSequence"])

(defn- update-parameter-value-labels
  "Updates the display strings in a single parameter value."
  [parameter-id {:keys [id display description arguments groups]}]
  (get-parameter-value parameter-id id)
  (let [update-vals (remove-nil-vals
                     {:description description
                      :label       display})]
    (when (seq update-vals)
      (sql/update parameter_values (set-fields update-vals) (where {:id id}))))
  (when (seq arguments)
    (dorun (map (partial update-parameter-value-labels parameter-id) arguments)))

  (when (seq groups)
    (dorun
     (map (partial update-parameter-value-labels parameter-id) groups))))

(defn- update-parameter-values
  "Updates the labels in parameter values."
  [parameter-id info-type arguments]
  (when-not (some (partial = info-type) generated-selection-list-info-types)
    (dorun (map (partial update-parameter-value-labels parameter-id) arguments))))

(defn- update-parameter-labels
  "Updates the labels in a parameter."
  [group-id {:keys [id description label arguments] :as parameter}]
  (let [{:keys [info_type]} (get-parameter-in-group group-id id)
        update-vals (remove-nil-vals
                     {:description description
                      :label       label})]
    (when (seq update-vals)
      (sql/update parameters (set-fields update-vals) (where {:id id})))
    (when (seq arguments)
      (update-parameter-values id info_type arguments))))

(defn- update-parameter-group-labels
  "Updates the labels in a parameter group."
  [task-id {:keys [id name description label] :as group}]
  (get-parameter-group-in-task task-id id)
  (let [update-vals (remove-nil-vals
                     {:name        name
                      :description description
                      :label       label})]
    (when (seq update-vals)
      (sql/update parameter_groups (set-fields update-vals) (where {:id id}))))
  (dorun (map (partial update-parameter-labels id) (:parameters group))))

(defn- update-task-labels
  "Updates the labels in a task."
  [{:keys [name description label groups]} task-id]
  (let [update-values (remove-nil-vals {:name        name
                                        :description description
                                        :label       label})]
    (when-not (empty? update-values)
      (sql/update tasks (set-fields update-values) (where {:id task-id}))))
  (dorun (map (partial update-parameter-group-labels task-id) groups)))

(defn update-app-labels
  "Updates the labels in an app."
  [{version-id :version_id :keys [id version] :as req}]
  (let [update-values (remove-nil-vals (select-keys req [:name :description]))
        version-id    (or version-id (app-meta/get-app-latest-version id))
        version-info  {:version version :version_id version-id}
        task          (get-single-task-for-app id version-id)]
    (when-not (empty? update-values)
      (sql/update apps (set-fields update-values) (where {:id id})))
    (app-meta/update-app-version version-info)
    (update-task-labels req (:id task))))
