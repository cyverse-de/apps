(ns apps.service.apps.tapis.listings
  (:require
   [apps.clients.iplant-groups :as ipg]
   [apps.persistence.app-metadata :as ap]
   [apps.service.apps.util :refer [to-qualified-app-id]]
   [apps.service.util :refer [apply-limit apply-offset format-job-stats sort-apps valid-uuid?]]
   [apps.util.conversions :refer [remove-nil-vals]]
   [clojure-commons.error-codes :as ce :refer [clj-http-error?]]
   [clojure.tools.logging :as log]
   [slingshot.slingshot :refer [try+]]))

;; TODO: restore the job stats gathering when we have a more efficient way to do this.
(defn- add-tapis-job-stats
  [app _params _admin?]
  (merge app {:job_count_completed 0
              :job_count           0
              :job_count_failed    0})
  #_(merge app (if admin?
                 (jobs-db/get-job-stats id params)
                 (jobs-db/get-public-job-stats id params))))

(defn- add-app-listing-job-stats
  [app-listing params admin?]
  (if admin?
    (update app-listing :apps (partial map (comp remove-nil-vals #(add-tapis-job-stats % params admin?))))
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
  [tapis app-id admin?]
  (-> (.getAppDetails tapis app-id)
      add-app-details-integrator-info
      (add-tapis-job-stats nil admin?)
      (format-job-stats admin?)
      remove-nil-vals))

(defn list-apps
  [tapis _category-id params]
  (-> (.listApps tapis)
      add-app-integrator-info
      (add-app-listing-job-stats params false)
      (sort-apps params {:default-sort-field "name"})
      (apply-offset params)
      (apply-limit params)
      (format-app-listing-job-stats false)))

(defn list-app
  [tapis app-id]
  (-> (.listApps tapis [app-id] {})
      add-app-integrator-info
      (select-keys [:total :apps])))

(defn list-apps-with-ontology
  [tapis term params admin?]
  (try+
   (-> (select-keys (.listAppsWithOntology tapis term) [:total :apps])
       add-app-integrator-info
       (add-app-listing-job-stats params admin?)
       (sort-apps params {:default-sort-field "name"})
       (apply-offset params)
       (apply-limit params)
       (format-app-listing-job-stats admin?))
   (catch [:error_code ce/ERR_UNAVAILABLE] _
     (log/error (:throwable &throw-context) "Tapis app listing timed out")
     nil)
   (catch clj-http-error? _
     (log/error (:throwable &throw-context) "HTTP error returned by Tapis")
     nil)))

(defn- fix-search-params
  [params admin?]
  (remove-nil-vals {:app-subset (when admin? (:app-subset params :public))}))

(defn search-apps
  [tapis search-term params admin?]
  (try+
   (-> (.searchApps tapis search-term (fix-search-params params admin?))
       add-app-integrator-info
       (add-app-listing-job-stats params admin?)
       (sort-apps params {:default-sort-field "name"})
       (apply-offset params)
       (apply-limit params)
       (format-app-listing-job-stats admin?))
   (catch [:error_code ce/ERR_UNAVAILABLE] _
     (log/error (:throwable &throw-context) "Tapis app search timed out")
     nil)
   (catch clj-http-error? _
     (log/error (:throwable &throw-context) "HTTP error returned by Tapis")
     nil)))

(defn load-app-tables
  [tapis app-ids]
  (try+
   (->> (.listApps tapis app-ids {})
        (:apps)
        (map (juxt to-qualified-app-id identity))
        (into {})
        (vector))
   (catch [:type :clojure-commons.exception/unavailable] _
     (log/warn (:throwable &throw-context) "Tapis app table retrieval timed out")
     [])
   (catch clj-http-error? _
     (log/error (:throwable &throw-context) "HTTP error returned by Tapis")
     [])))

(defn- prep-tapis-param
  [step-id tapis-app-id param]
  (let [is-file-param? (re-find #"^(?:File|Folder)" (:type param))]
    {:data_format     (when is-file-param? "Unspecified")
     :info_type       (when is-file-param? "PlainText")
     :omit_if_blank   false
     :is_visible      (:isVisible param)
     :name            (:name param)
     :is_implicit     false
     :external_app_id tapis-app-id
     :ordering        (:order param)
     :type            (:type param)
     :step_id         step-id
     :label           (:label param)
     :id              (:id param)
     :description     (:description param)
     :default_value   (:defaultValue param)}))

(defn- load-tapis-pipeline-step-params
  [tapis-client {step-id :step_id tapis-app-id :external_app_id}]
  (->> (.getApp tapis-client tapis-app-id)
       (:groups)
       (mapcat :parameters)
       (map (partial prep-tapis-param step-id tapis-app-id))))

(defn- load-tapis-pipeline-params
  [tapis-client app-version-id]
  (->> (ap/load-app-steps app-version-id)
       (remove (comp nil? :external_app_id))
       (mapcat (partial load-tapis-pipeline-step-params tapis-client))))

(defn- load-tapis-app-params
  [tapis-client app-id]
  (->> (.getApp tapis-client app-id)
       (:groups)
       (mapcat :parameters)
       (map (partial prep-tapis-param nil app-id))))

(defn get-param-definitions
  [tapis app-id version-id]
  (if (and version-id (valid-uuid? app-id))
    (load-tapis-pipeline-params tapis version-id)
    (load-tapis-app-params tapis app-id)))
