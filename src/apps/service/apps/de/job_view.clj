(ns apps.service.apps.de.job-view
  (:require
   [apps.clients.data-info :as data-info]
   [apps.metadata.params :as mp]
   [apps.constants :as ac]
   [apps.persistence.app-metadata :as amp]
   [apps.service.apps.de.constants :as c]
   [apps.service.apps.de.limits :as limits]
   [apps.service.apps.de.validation :refer [verify-app-permission]]
   [apps.service.apps.jobs.util :as util]
   [apps.service.apps.util :refer [paths-accessible?]]
   [apps.util.service :as service]
   [apps.util.config :as config]
   [apps.util.db :as db]
   [apps.util.conversions :refer [remove-nil-vals]]
   [clojure.java.jdbc :as jdbc]
   [clojure-commons.exception-util :as cxu]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [korma.core :refer [fields join order select where]]
   [slingshot.slingshot :refer [try+ throw+]]))

(defn- format-step-resource-requirements
  [requirements step-number add-defaults?]
  (if add-defaults?
    (merge {:max_cpu_cores (config/default-cpu-limit)
            :memory_limit (config/default-memory-limit)
            :max_gpus (config/default-gpu-limit)
            :step_number step-number}
           requirements)
    (assoc requirements :step_number step-number)))

(defn- get-step-resource-requirements
  [app {task-id :task_id step-number :step_number}]
  (let [requirements (amp/get-resource-requirements-for-task task-id)
        add-defaults? (= (:overall_job_type app) ac/interactive-tool-type)]
    (format-step-resource-requirements requirements step-number add-defaults?)))

(defn- mapped-input-subselect
  [step-id]
  (-> (h/select :*)
      (h/from [:workflow_io_maps :wm])
      (h/join [:input_output_mapping :iom] [:= :wm.id :iom.mapping_id])
      (h/where [:and
                [:= :iom.input :p.id]
                [:= :wm.target_step step-id]])))

(defn- get-parameters-query
  [step-id group-id include-hidden-params?]
  (-> (mp/hsql-params-base-query)
      (h/order-by :p.display-order)
      (h/where [:and
                [:= :p.parameter_group_id group-id]
                (when include-hidden-params? :p.is_visible)
                [:and
                 [:not [:exists (mapped-input-subselect step-id)]]
                 [:or
                  [:= :p.value_type "Input"]
                  [:not [:coalesce :is_implicit false]]]]])
      (sql/format)))

(defn- get-parameters
  [step-id group-id include-hidden-parameters]
  (db/with-transaction [tx]
    (jdbc/query tx (get-parameters-query step-id group-id include-hidden-parameters))))

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
  [user {:keys [id name version_id] :as app} include-hidden-params?]
  (let [app-steps           (get-steps version_id)
        limit-check-results (limits/load-limit-check-results user)]
    (-> (select-keys app [:id :name :description :disabled :deleted :version :version_id])
        (assoc :label name
               :versions (amp/list-app-versions id)
               :requirements (map (partial get-step-resource-requirements app) app-steps)
               :groups (remove (comp empty? :parameters) (format-steps user include-hidden-params? app-steps))
               :app_type "DE"
               :system_id c/system-id
               :limitChecks (limits/format-app-limit-check-results limit-check-results app)))))

(defn- validate-hidden-inputs
  [user app-version-id]
  (when-let [paths (mapv :default_value (filter util/input? (mp/load-hidden-params app-version-id)))]
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
  "This service obtains an app description, for the app's latest version,
   in a format suitable for building the job submission UI."
  [user app-id include-hidden-params?]
  (let [app (amp/get-app app-id)]
    (verify-app-permission user app "read")
    (validate-hidden-inputs user (:version_id app))
    (format-app user app include-hidden-params?)))

(defn get-app-version
  "This service obtains an app version's description in a format suitable for
   building the job submission UI."
  [user app-id version-id]
  (let [app (amp/get-app-version app-id version-id)]
    (verify-app-permission user app "read")
    (validate-hidden-inputs user (:version_id app))
    (format-app user app false)))
