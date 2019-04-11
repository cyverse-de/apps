(ns apps.service.apps.jobs.params
  (:use [apps.util.conversions :only [remove-nil-vals]]
        [kameleon.uuids :only [uuidify]]
        [slingshot.slingshot :only [throw+]])
  (:require [cheshire.core :as cheshire]
            [clojure.string :as string]
            [clojure-commons.exception-util :as cxu]
            [apps.persistence.app-metadata :as ap]
            [apps.service.apps.jobs.util :as ju]
            [apps.service.util :as util]))

(defn- get-job-submission
  [job]
  (let [submission (:submission job)]
    (when-not submission
      (throw+ {:type  :clojure-commons.exception/not-found
               :error (str "Job submission values could not be found for " (:id job))}))
    (cheshire/decode (.getValue submission) true)))

(defn- load-mapped-params
  [app-id]
  (if (util/uuid? app-id)
    (let [format-id     (partial string/join "_")
          get-source-id (comp format-id (juxt :source_id (some-fn :output_id :external_output_id)))
          get-target-id (comp format-id (juxt :target_id (some-fn :input_id :external_input_id)))
          get-ids       (juxt get-source-id get-target-id)]
      (set (mapcat get-ids (ap/load-app-mappings app-id))))
    #{}))

(defn- get-full-param-id
  [{step-id :step_id param-id :id :as param}]
  (if-not (string/blank? (str step-id))
    (str step-id "_" param-id)
    (str param-id)))

(defn- remove-mapped-params
  [app-id params]
  (let [mapped-params (load-mapped-params app-id)]
    (remove (comp mapped-params get-full-param-id) params)))

(def property-types-to-omit #{"Info"})

(defn- implicit-output?
  [param]
  (and (= "Output" (:value_type param)) (:is_implicit param)))

(defn- omit-param?
  [param]
  (or (contains? property-types-to-omit (:type param))
      (implicit-output? param)))

(defn- blank->empty-str
  [value]
  (if (string/blank? value)
    ""
    value))

(defn- format-list-selection
  [value]
  {:value
   (assoc value
     :display (:display value (:name value ""))
     :value   (:value value (:name value "")))})

(defn- format-scalar-value
  [value]
  {:value (blank->empty-str (str value))})

(defn- format-config-values
  [config-values]
  (cond (sequential? config-values) (map format-scalar-value config-values)
        (map? config-values)        [(format-list-selection config-values)]
        :else                       [(format-scalar-value config-values)]))

(defn- format-job-param-values
  [config config-key default-value]
  (if-let [config-values (config-key config)]
    (format-config-values config-values)
    [default-value]))

(defn- format-job-param-for-value
  [full-param-id default-value param value]
  (remove-nil-vals
   {:param_type       (:type param)
    :data_format      (blank->empty-str (:data_format param))
    :info_type        (blank->empty-str (:info_type param))
    :is_visible       (:is_visible param true)
    :param_id         (:id param)
    :full_param_id    full-param-id
    :param_value      value
    :is_default_value (= value default-value)
    :param_name       (:label param)}))

(defn- format-job-param
  [config param]
  (let [full-param-id (get-full-param-id param)
        config-key    (keyword full-param-id)
        default-value (first (format-config-values (:default_value param)))
        values        (format-job-param-values config config-key default-value)]
    (map (partial format-job-param-for-value full-param-id default-value param) values)))

(defn get-parameter-values
  [apps-client {system-id :system_id app-id :app_id :as job}]
  (let [config (:config (get-job-submission job))]
    (->> (.getParamDefinitions apps-client system-id app-id)
         (remove-mapped-params app-id)
         (remove omit-param?)
         (mapcat (partial format-job-param config)))))

(defn- update-prop
  [config prop]
  (let [id        (keyword (:id prop))
        get-value (if (ju/input? prop) (constantly {:path (config id)}) #(config id))]
    (if (contains? config id)
      (let [prop-value (get-value)]
        (assoc prop
          :value        prop-value
          :defaultValue prop-value))
      prop)))

(defn- update-app-props
  [config props]
  (map (partial update-prop config) props))

(defn- update-app-group
  [config group]
  (update-in group [:parameters] (partial update-app-props config)))

(defn- update-app-groups
  [config groups]
  (map (partial update-app-group config) groups))

(defn get-job-relaunch-info
  [apps-client {system-id :system_id app-id :app_id :as job}]
  (let [submission (get-job-submission job)]
    (update-in (assoc (.getAppJobView apps-client system-id app-id) :debug (:debug submission false))
               [:groups]
               (partial update-app-groups (:config submission)))))

(defn get-submission-launch-info
  [apps-client {system-id :system_id app-id :app_id :as submission}]
  (when-not system-id (cxu/internal-system-error "no system ID assocaited with submission"))
  (when-not app-id (cxu/internal-system-error "no app ID associated with submission"))
  (update (assoc (.getAppJobView apps-client system-id (uuidify app-id)) :debug (:debug submission false))
          :groups
          (partial update-app-groups (:config submission))))

(defn get-job-config
  [job]
  (:config (get-job-submission job)))
