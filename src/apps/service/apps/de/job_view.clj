(ns apps.service.apps.de.job-view
  (:use [apps.service.apps.de.validation :only [verify-app-permission]]
        [apps.service.apps.util :only [paths-accessible?]]
        [apps.util.assertions :only [assert-not-nil]]
        [apps.util.conversions :only [remove-nil-vals]]
        [korma.core :exclude [update]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.data-info :as data-info]
            [apps.metadata.params :as mp]
            [apps.persistence.app-metadata :as amp]
            [apps.service.apps.de.constants :as c]
            [apps.service.apps.de.limits :as limits]
            [apps.service.apps.jobs.util :as util]
            [apps.util.service :as service]
            [clojure-commons.exception-util :as cxu]))

(defn get-step-resource-requirements
  [{task-id :task_id step-number :step_number}]
  (let [requirements (amp/get-resource-requirements-for-task task-id)]
    (assoc requirements :step_number step-number)))

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

(defn- get-default-value
  [user type values]
  (let [default-value (mp/get-default-value type values)]
    (if (util/input-type? type)
      (when (and default-value (paths-accessible? user default-value))
        {:path default-value})
      default-value)))

(defn- format-parameter
  [user step {:keys [id type] :as parameter}]
  (let [values (mp/get-param-values (:id parameter))]
    (remove-nil-vals
     {:arguments    (mp/format-param-values type values)
      :defaultValue (get-default-value user type values)
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
  [user name-prefix include-hidden-params? step group]
  (let [params (get-parameters (:id step) (:id group) include-hidden-params?)]
    (remove-nil-vals
     {:id           (:id group)
      :name         (str name-prefix (:name group))
      :label        (str name-prefix (:label group))
      :parameters   (mapv (partial format-parameter user step) params)
      :step_number  (:step_number step)})))

(defn- format-groups
  [user name-prefix include-hidden-params? step]
  (mapv (partial format-group user name-prefix include-hidden-params? step) (get-groups (:id step))))

(defn- get-steps
  [app-version-id]
  (select [:app_steps :s]
          (join :inner [:tasks :t] {:s.task_id :t.id})
          (fields :s.id [:s.step :step_number] :s.task_id [:t.name :task_name])
          (where {:app_version_id app-version-id})
          (order :step)))

(defn- format-steps
  [user include-hidden-params? app-steps]
  (let [multistep?        (> (count app-steps) 1)
        group-name-prefix (fn [{task-name :task_name}] (if multistep? (str task-name " - ") ""))]
    (doall (mapcat (fn [step] (format-groups user (group-name-prefix step) include-hidden-params? step)) app-steps))))

(defn- format-app
  [user {:keys [name version_id] :as app} include-hidden-params?]
  (let [app-steps           (get-steps version_id)
        limit-check-results (limits/load-limit-check-results user)]
    (-> (select-keys app [:id :name :description :disabled :deleted])
        (assoc :label name
               :requirements (map get-step-resource-requirements app-steps)
               :groups (remove (comp empty? :parameters) (format-steps user include-hidden-params? app-steps))
               :app_type "DE"
               :system_id c/system-id
               :limitChecks (limits/format-app-limit-check-results limit-check-results app)))))

(defn- validate-hidden-inputs [user app-id]
  (when-let [paths (mapv :default_value (filter util/input? (mp/load-hidden-params app-id)))]
    (try+
     (data-info/get-path-info user :paths paths :validation-behavior "read" :filter-include "path")
     (catch [:status 500] e
       (let [error-code (:error_code (service/parse-json (:body e)))]
         (condp = error-code
           "ERR_NOT_READABLE"
           (cxu/forbidden (str "The app you are trying to use references one or more input files that you cannot "
                               "access. Please contact the app integrator."))

           "ERR_DOES_NOT_EXIST"
           (cxu/bad-request (str "The app you are trying to use references one or more input files that cannot be "
                                 "found. Please contact the app integrator."))

           (throw+)))))))

(defn get-app
  "This service obtains an app description in a format that is suitable for building the job
  submission UI."
  [user app-id include-hidden-params?]
  (let [app (amp/get-app app-id)]
    (verify-app-permission user app "read")
    (validate-hidden-inputs user app-id)
    (format-app user app include-hidden-params?)))
