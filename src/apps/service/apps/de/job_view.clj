(ns apps.service.apps.de.job-view
  (:use [apps.service.apps.de.validation :only [verify-app-permission]]
        [apps.util.conversions :only [remove-nil-vals]]
        [korma.core :exclude [update]])
  (:require [apps.metadata.params :as mp]
            [apps.persistence.app-metadata :as amp]
            [apps.service.apps.de.constants :as c]))

(defn- mapped-input-subselect
  [step-id]
  (subselect [:workflow_io_maps :wm]
             (join [:input_output_mapping :iom] {:wm.id :iom.mapping_id})
             (where {:iom.input      :p.id
                     :wm.target_step step-id})))

(defn- add-hidden-parameters-clause
  [query include-hidden-params?]
  (if-not include-hidden-params?
    (where query {:p.is_visible true})
    query))

(defn- get-parameters
  [step-id group-id include-hidden-params?]
  (-> (mp/params-base-query)
      (order :p.display_order)
      (where {:p.parameter_group_id group-id})
      (add-hidden-parameters-clause include-hidden-params?)
      (where (and (not (exists (mapped-input-subselect step-id)))
                  (or {:value_type  "Input"}
                      {:is_implicit nil}
                      {:is_implicit false})))
      select))

(defn- format-parameter
  [step {:keys [id type] :as parameter}]
  (let [values (mp/get-param-values (:id parameter))]
    (remove-nil-vals
     {:arguments    (mp/format-param-values type values)
      :defaultValue (mp/get-default-value type values)
      :description  (:description parameter)
      :id           (str (:id step) "_" (:id parameter))
      :isVisible    (:is_visible parameter)
      :label        (:label parameter)
      :name         (:name parameter)
      :required     (:required parameter)
      :type         (:type parameter)
      :validators   (mp/get-validators id)})))

(defn- get-groups
  [step-id]
  (select [:parameter_groups :g]
          (order :display_order)
          (join :inner [:tasks :t] {:g.task_id :t.id})
          (join :inner [:app_steps :s] {:t.id :s.task_id})
          (fields :g.id :g.label)
          (where {:s.id step-id})))

(defn- format-group
  [name-prefix include-hidden-params? step group]
  {:id          (:id group)
   :name        (str name-prefix (:name group))
   :label       (str name-prefix (:label group))
   :parameters  (mapv (partial format-parameter step) (get-parameters (:id step) (:id group) include-hidden-params?))
   :step_number (:step_number step)})

(defn- format-groups
  [name-prefix include-hidden-params? step]
  (mapv (partial format-group name-prefix include-hidden-params? step) (get-groups (:id step))))

(defn- get-steps
  [app-id]
  (select [:app_steps :s]
          (join :inner [:tasks :t] {:s.task_id :t.id})
          (fields :s.id [:s.step :step_number] :s.task_id [:t.name :task_name])
          (where {:app_id app-id})))

(defn- format-steps
  [app-id include-hidden-params?]
  (let [app-steps         (get-steps app-id)
        multistep?        (> (count app-steps) 1)
        group-name-prefix (fn [{task-name :task_name}] (if multistep? (str task-name " - ") ""))]
    (doall (mapcat (fn [step] (format-groups (group-name-prefix step) include-hidden-params? step)) app-steps))))

(defn- format-app
  [{app-id :id name :name :as app} include-hidden-params?]
  (-> (select-keys app [:id :name :description :disabled :deleted])
      (assoc :label     name
             :groups    (remove (comp empty? :parameters) (format-steps app-id include-hidden-params?))
             :app_type  "DE"
             :system_id c/system-id)))

(defn get-app
  "This service obtains an app description in a format that is suitable for building the job
  submission UI."
  [user app-id include-hidden-params?]
  (let [app (amp/get-app app-id)]
    (verify-app-permission user app "read")
    (format-app app include-hidden-params?)))
