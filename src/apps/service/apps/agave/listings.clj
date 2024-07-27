(ns apps.service.apps.agave.listings
  (:use [apps.service.apps.util :only [to-qualified-app-id]]
        [apps.service.util :only [sort-apps apply-offset apply-limit format-job-stats valid-uuid?]]
        [apps.util.conversions :only [remove-nil-vals]]
        [slingshot.slingshot :only [try+]])
  (:require [apps.clients.iplant-groups :as ipg]
            [apps.persistence.app-metadata :as ap]
            [apps.persistence.jobs :as jobs-db]
            [clojure.tools.logging :as log]
            [clojure-commons.error-codes :as ce :refer [clj-http-error?]]))

;; TODO: restore the job stats gathering when we have a more efficient way to do this.
(defn- add-agave-job-stats
  [{:keys [id] :as app} params admin?]
  (merge app {:job_count_completed 0
              :job_count           0
              :job_count_failed    0})
  #_(merge app (if admin?
               (jobs-db/get-job-stats id params)
               (jobs-db/get-public-job-stats id params))))

(defn- add-app-listing-job-stats
  [app-listing params admin?]
  (if admin?
    (update app-listing :apps (partial map (comp remove-nil-vals #(add-agave-job-stats % params admin?))))
    app-listing))

(defn- format-app-listing-job-stats
  [app-listing admin?]
  (if admin?
    (update app-listing :apps (partial map #(format-job-stats % admin?)))
    app-listing))

(defn- add-app-integrator-info
  ([app-listing]
   (let [subject-info-for (ipg/lookup-subjects (map :owner (:apps app-listing)))
         add-integrator   (partial add-app-integrator-info subject-info-for)]
     (update app-listing :apps (partial mapv add-integrator))))
  ([subject-info-for {:keys [owner] :as app-listing}]
   (if-let [subject-info (some-> owner subject-info-for)]
     (assoc (dissoc app-listing :owner)
            :integrator_name  (:name subject-info (:integrator_name app-listing))
            :integrator_email (:email subject-info (:integrator_email app-listing)))
     (dissoc app-listing :owner))))

(defn- add-app-details-integrator-info
  [{:keys [owner] :as app-details}]
  (let [subject-info-for (ipg/lookup-subjects [owner])]
    (add-app-integrator-info subject-info-for app-details)))

(defn get-app-details
  [agave app-id admin?]
  (-> (.getAppDetails agave app-id)
      add-app-details-integrator-info
      (add-agave-job-stats nil admin?)
      (format-job-stats admin?)
      remove-nil-vals))

(defn list-apps
  [agave category-id params]
  (-> (.listApps agave)
      add-app-integrator-info
      (add-app-listing-job-stats params false)
      (sort-apps params {:default-sort-field "name"})
      (apply-offset params)
      (apply-limit params)
      (format-app-listing-job-stats false)))

(defn list-app
  [agave app-id]
  (-> (.listApps agave [app-id] {})
      add-app-integrator-info
      (select-keys [:total :apps])))

(defn list-apps-with-ontology
  [agave term params admin?]
  (try+
   (-> (select-keys (.listAppsWithOntology agave term) [:total :apps])
       add-app-integrator-info
       (add-app-listing-job-stats params admin?)
       (sort-apps params {:default-sort-field "name"})
       (apply-offset params)
       (apply-limit params)
       (format-app-listing-job-stats admin?))
   (catch [:error_code ce/ERR_UNAVAILABLE] _
     (log/error (:throwable &throw-context) "Agave app listing timed out")
     nil)
   (catch clj-http-error? _
     (log/error (:throwable &throw-context) "HTTP error returned by Agave")
     nil)))

(defn- fix-search-params
  [params admin?]
  (remove-nil-vals {:app-subset (when admin? (:app-subset params :public))}))

(defn search-apps
  [agave search-term params admin?]
  (try+
   (-> (.searchApps agave search-term (fix-search-params params admin?))
       add-app-integrator-info
       (add-app-listing-job-stats params admin?)
       (sort-apps params {:default-sort-field "name"})
       (apply-offset params)
       (apply-limit params)
       (format-app-listing-job-stats admin?))
   (catch [:error_code ce/ERR_UNAVAILABLE] _
     (log/error (:throwable &throw-context) "Agave app search timed out")
     nil)
   (catch clj-http-error? _
     (log/error (:throwable &throw-context) "HTTP error returned by Agave")
     nil)))

(defn load-app-tables
  [agave app-ids]
  (try+
   (->> (.listApps agave app-ids {})
        (:apps)
        (map (juxt to-qualified-app-id identity))
        (into {})
        (vector))
   (catch [:type :clojure-commons.exception/unavailable] _
     (log/warn (:throwable &throw-context) "Agave app table retrieval timed out")
     [])
   (catch clj-http-error? _
     (log/error (:throwable &throw-context) "HTTP error returned by Agave")
     [])))

(defn- prep-agave-param
  [step-id agave-app-id param]
  (let [is-file-param? (re-find #"^(?:File|Folder)" (:type param))]
    {:data_format     (when is-file-param? "Unspecified")
     :info_type       (when is-file-param? "PlainText")
     :omit_if_blank   false
     :is_visible      (:isVisible param)
     :name            (:name param)
     :is_implicit     false
     :external_app_id agave-app-id
     :ordering        (:order param)
     :type            (:type param)
     :step_id         step-id
     :label           (:label param)
     :id              (:id param)
     :description     (:description param)
     :default_value   (:defaultValue param)}))

(defn- load-agave-pipeline-step-params
  [agave-client {step-id :step_id agave-app-id :external_app_id}]
  (->> (.getApp agave-client agave-app-id)
       (:groups)
       (mapcat :parameters)
       (map (partial prep-agave-param step-id agave-app-id))))

(defn- load-agave-pipeline-params
  [agave-client app-version-id]
  (->> (ap/load-app-steps app-version-id)
       (remove (comp nil? :external_app_id))
       (mapcat (partial load-agave-pipeline-step-params agave-client))))

(defn- load-agave-app-params
  [agave-client app-id]
  (->> (.getApp agave-client app-id)
       (:groups)
       (mapcat :parameters)
       (map (partial prep-agave-param nil app-id))))

(defn get-param-definitions
  [agave app-id version-id]
  (if (and version-id (valid-uuid? app-id))
    (load-agave-pipeline-params agave version-id)
    (load-agave-app-params agave app-id)))
