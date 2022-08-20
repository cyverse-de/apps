(ns apps.service.apps
  (:use [apps.constants :only [de-system-id]]
        [apps.service.apps-client :only [get-apps-client get-apps-client-for-username]]
        [apps.util.conversions :only [remove-nil-vals]]
        [apps.util.db :only [transaction]]
        [slingshot.slingshot :only [try+]]
        [service-logging.thread-context :only [with-logging-context]])
  (:require [clojure-commons.exception-util :as cxu]
            [clojure.walk :as walk]
            [apps.clients.notifications :as cn]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.jobs :as jobs]
            [apps.util.json :as json-util]))

(defn get-app-categories
  [user params]
  (let [client (get-apps-client user "type=apps")]
    {:categories (transaction
                   (walk/prewalk (fn [x] (if (or (future? x) (delay? x)) (deref x) x))
                     (.listAppCategories client params)))}))

(defn list-apps-in-category
  [user system-id category-id params]
  (let [state-info (str "type=apps&system-id=" system-id "&category-id=" category-id)
        client     (get-apps-client user state-info)]
    (.listAppsInCategory client system-id category-id params)))

(defn list-apps-under-hierarchy
  [user root-iri attr params]
  (.listAppsUnderHierarchy (get-apps-client user) root-iri attr params))

(defn admin-list-apps-under-hierarchy
  [user ontology-version root-iri attr params]
  (.adminListAppsUnderHierarchy (get-apps-client user) ontology-version root-iri attr params))

(defn list-apps-in-community
  [user community-id params]
  (.listAppsInCommunity (get-apps-client user) community-id params))

(defn admin-list-apps-in-community
  [user community-id params]
  (.adminListAppsInCommunity (get-apps-client user) community-id params))

(defn search-apps
  [user {:keys [search] :as params}]
  (.searchApps (get-apps-client user) search params))

(defn list-single-app
  [user system-id app-id]
  (.listSingleApp (get-apps-client user) system-id app-id))

(defn admin-search-apps
  [user {:keys [search] :as params}]
  (.adminSearchApps (get-apps-client user) search params))

(defn add-app
  [user system-id app]
  (.addApp (get-apps-client user) system-id app))

(defn add-app-version
  [user system-id app admin?]
  (.addAppVersion (get-apps-client user) system-id app admin?))

(defn preview-command-line
  [user system-id app]
  (.previewCommandLine (get-apps-client user) system-id app))

(defn delete-apps
  [user deletion-request]
  (.deleteApps (get-apps-client user) deletion-request))

(defn get-app-job-view
  [user system-id app-id]
  (.getAppJobView (get-apps-client user) system-id app-id))

(defn get-app-version-job-view
  [user system-id app-id version-id]
  (.getAppVersionJobView (get-apps-client user) system-id app-id version-id))

(defn delete-app
  [user system-id app-id]
  (.deleteApp (get-apps-client user) system-id app-id))

(defn delete-app-version
  [user system-id app-id app-version-id]
  (.deleteAppVersion (get-apps-client user) system-id app-id app-version-id))

(defn relabel-app
  [user system-id app]
  (.relabelApp (get-apps-client user) system-id app))

(defn update-app
  [user system-id app]
  (.updateApp (get-apps-client user) system-id app))

(defn copy-app
  [user system-id app-id]
  (.copyApp (get-apps-client user) system-id app-id))

(defn copy-app-version
  [user system-id app-id version-id]
  (.copyAppVersion (get-apps-client user) system-id app-id version-id))

(defn get-app-details
  [user system-id app-id]
  (.getAppDetails (get-apps-client user) system-id app-id false))

(defn get-app-version-details
  [user system-id app-id app-version-id]
  (.getAppVersionDetails (get-apps-client user) system-id app-id app-version-id false))

(defn admin-get-app-details
  [user system-id app-id]
  (.getAppDetails (get-apps-client user) system-id app-id true))

(defn admin-get-app-version-details
  [user system-id app-id app-version-id]
  (.getAppVersionDetails (get-apps-client user) system-id app-id app-version-id true))

(defn remove-app-favorite
  [user system-id app-id]
  (.removeAppFavorite (get-apps-client user) system-id app-id))

(defn add-app-favorite
  [user system-id app-id]
  (.addAppFavorite (get-apps-client user) system-id app-id))

(defn app-publishable?
  [user system-id app-id & admin?]
  (let [[publishable? reason] (.isAppPublishable (get-apps-client user) system-id app-id admin?)]
    (remove-nil-vals {:publishable publishable? :reason reason})))

(defn validate-app-publishable
  [user system-id app-id & admin?]
  (let [[publishable? reason] (.isAppPublishable (get-apps-client user) system-id app-id admin?)]
    (when-not publishable?
      (cxu/bad-request reason))))

(defn list-tools-in-untrusted-registries
  [user system-id app-id]
  (.listToolsInUntrustedRegistries (get-apps-client user) system-id app-id))

(defn uses-tools-in-untrusted-registries?
  [user system-id app-id]
  (.usesToolsInUntrustedRegistries (get-apps-client user) system-id app-id))

(defn create-publication-request
  [user system-id app untrusted-tools]
  (.createPublicationRequest (get-apps-client user) system-id app untrusted-tools))

(defn make-app-public
  [user system-id app]
  (.makeAppPublic (get-apps-client user) system-id app))

(defn delete-app-rating
  [user system-id app-id]
  (.deleteAppRating (get-apps-client user) system-id app-id))

(defn rate-app
  [user system-id app-id rating]
  (.rateApp (get-apps-client user) system-id app-id rating))

(defn get-app-task-listing
  [user system-id app-id]
  (.getAppTaskListing (get-apps-client user) system-id app-id))

(defn get-app-version-task-listing
  [user system-id app-id version-id]
  (.getAppVersionTaskListing (get-apps-client user) system-id app-id version-id))

(defn get-app-tool-listing
  [user system-id app-id]
  (.getAppToolListing (get-apps-client user) system-id app-id))

(defn get-app-version-tool-listing
  [user system-id app-id version-id]
  (.getAppVersionToolListing (get-apps-client user) system-id app-id version-id))

(defn get-app-ui
  ([user system-id app-id]
   (.getAppUi (get-apps-client user) system-id app-id))
  ([user system-id app-id version-id]
   (.getAppVersionUi (get-apps-client user) system-id app-id version-id)))

(defn add-pipeline
  [user pipeline]
  (.addPipeline (get-apps-client user) pipeline))

(defn add-pipeline-version
  [user pipeline admin?]
  (.addPipelineVersion (get-apps-client user) pipeline admin?))

(defn update-pipeline
  [user pipeline]
  (.updatePipeline (get-apps-client user) pipeline))

(defn copy-pipeline
  [user app-id]
  (.copyPipeline (get-apps-client user) app-id))

(defn edit-pipeline
  ([user app-id]
   (.editPipeline (get-apps-client user) app-id))
  ([user app-id version-id]
   (.editPipelineVersion (get-apps-client user) app-id version-id)))

(defn list-jobs
  [user params]
  (.listJobs (get-apps-client user) params))

(defn list-job-stats
  [user params]
  (.listJobStats (get-apps-client user) params))

(defn- format-job-submission-response
  [job-info]
  (-> job-info
      (select-keys [:id :name :status :startdate :missing-paths])
      (clojure.set/rename-keys {:startdate :start-date})))

(defn submit-job
  [{username :shortUsername email :email :as user} submission]
  (json-util/log-json "submission" submission)
  (let [job-info (jobs/submit (get-apps-client user) user submission)]
    (cn/send-job-status-update username email job-info)
    (format-job-submission-response job-info)))

(defn update-job-status
  ([external-id]
   (jobs/validate-job-status-update-step-count external-id)
   (transaction
    (jp/set-lock-timeout)
    (let [updates (jp/get-unpropagated-job-status-updates external-id)]
      (when (seq updates)
        (let [job-id      (:job_id (first (jp/get-job-steps-by-external-id external-id)))
              job-step    (jobs/lock-job-step job-id external-id)
              job         (jobs/lock-job job-id)
              batch       (when-let [parent-id (:parent_id job)] (jp/get-job-by-id parent-id))
              apps-client (get-apps-client-for-username (:username job))]
          (reduce (fn [job-step {status :status sent-on :sent_on}]
                    (if (jp/status-follows? status (:status job-step))
                      (let [end-date (when (jp/completed? status) (str sent-on))]
                        (jobs/update-job-status apps-client job-step job batch status end-date))
                      job-step))
                  job-step updates)
          (jp/mark-job-status-updates-propagated (mapv :id updates))
          nil)))))
  ([job-id external-id status end-date]
   (transaction
    (let [job-step (jobs/lock-job-step job-id external-id)
          job      (jobs/lock-job job-id)
          batch    (when-let [parent-id (:parent_id job)] (jp/get-job-by-id parent-id))]
      (-> (get-apps-client-for-username (:username job))
          (jobs/update-job-status job-step job batch status end-date))))))

(def logging-context-map (ref {}))

(defn set-logging-context!
  "Sets the logging ThreadContext for the sync-job-statuses function, which is
  run in a new thread as a task."
  [cm]
  (dosync (ref-set logging-context-map cm)))

(defn update-job
  [user job-id body]
  (jobs/update-job user job-id body))

(defn delete-job
  [user job-id]
  (jobs/delete-job (get-apps-client user) user job-id)
  nil)

(defn delete-jobs
  [user body]
  (jobs/delete-jobs (get-apps-client user) user (:analyses body))
  nil)

(defn get-job-history
  [user job-id]
  (jobs/get-job-history (get-apps-client user) user job-id))

(defn get-parameter-values
  [user job-id]
  (jobs/get-parameter-values (get-apps-client user) user job-id))

(defn get-job-relaunch-info
  [user job-id]
  (jobs/get-job-relaunch-info (get-apps-client user) user job-id))

(defn get-submission-launch-info
  [user submission-id]
  (jobs/get-submission-launch-info (get-apps-client user) user submission-id))

(defn relaunch-jobs
  [user job-ids]
  (jobs/relaunch-jobs (get-apps-client user) user job-ids))

(defn stop-job
  [user job-id params]
  (let [status (:job_status params jp/canceled-status)]
    (jobs/stop-job (get-apps-client user) user job-id status))
  {:id job-id})

(defn list-job-steps
  [user job-id]
  (jobs/list-job-steps user job-id))

(defn categorize-apps
  [user body]
  (transaction (.categorizeApps (get-apps-client user) body)))

(defn list-app-publication-requests
  [user params]
  {:publication_requests (.listAppPublicationRequests (get-apps-client user) params)})

(defn permanently-delete-apps
  [user body]
  (.permanentlyDeleteApps (get-apps-client user) body))

(defn admin-delete-app
  [user system-id app-id]
  (.adminDeleteApp (get-apps-client user) system-id app-id))

(defn admin-update-app
  [user system-id body]
  (let [apps-client (get-apps-client user)]
    (.adminUpdateApp apps-client system-id body)
    (.getAppDetails apps-client system-id (:id body) true)))

(defn admin-bless-app
  [user system-id app-id]
  (.adminBlessApp (get-apps-client user) system-id app-id))

(defn admin-remove-app-blessing
  [user system-id app-id]
  (.adminRemoveAppBlessing (get-apps-client user) system-id app-id))

(defn get-admin-app-categories
  [user params]
  (walk/prewalk (fn [x] (if (or (future? x) (delay? x)) (deref x) x))
    {:categories (.getAdminAppCategories (get-apps-client user) params)}))

(defn search-admin-app-categories
  [user params]
  {:categories (.searchAdminAppCategories (get-apps-client user) params)})

(defn admin-add-category
  [user system-id body]
  (let [apps-client (get-apps-client user)]
    (.listAppsInCategory apps-client system-id (.adminAddCategory apps-client system-id body) {})))

(defn admin-delete-category
  [user system-id category-id]
  (.adminDeleteCategory (get-apps-client user) system-id category-id))

(defn admin-update-category
  [user system-id {:keys [id] :as body}]
  (let [apps-client (get-apps-client user)]
    (.adminUpdateCategory apps-client system-id body)
    (.listAppsInCategory apps-client system-id id {})))

(defn get-app-docs
  [user system-id app-id]
  (.getAppDocs (get-apps-client user) system-id app-id))

(defn get-app-version-docs
  [user system-id app-id version-id]
  (.getAppVersionDocs (get-apps-client user) system-id app-id version-id))

(defn get-app-integration-data
  [user system-id app-id]
  (.getAppIntegrationData (get-apps-client user) system-id app-id))

(defn get-app-version-integration-data
  [user system-id app-id version-id]
  (.getAppVersionIntegrationData (get-apps-client user) system-id app-id version-id))

(defn get-tool-integration-data
  [user system-id tool-id]
  (.getToolIntegrationData (get-apps-client user) system-id tool-id))

(defn update-app-integration-data
  [user system-id app-id integration-data-id]
  (.updateAppIntegrationData (get-apps-client user) system-id app-id integration-data-id))

(defn update-app-version-integration-data
  [user system-id app-id version-id integration-data-id]
  (.updateAppVersionIntegrationData (get-apps-client user) system-id app-id version-id integration-data-id))

(defn update-tool-integration-data
  [user system-id tool-id integration-data-id]
  (.updateToolIntegrationData (get-apps-client user) system-id tool-id integration-data-id))

(defn owner-edit-app-docs
  [user system-id app-id body]
  (.ownerEditAppDocs (get-apps-client user) system-id app-id body))

(defn owner-edit-app-version-docs
  [user system-id app-id version-id body]
  (.ownerEditAppVersionDocs (get-apps-client user) system-id app-id version-id body))

(defn owner-add-app-docs
  [user system-id app-id body]
  (.ownerAddAppDocs (get-apps-client user) system-id app-id body))

(defn owner-add-app-version-docs
  [user system-id app-id version-id body]
  (.ownerAddAppVersionDocs (get-apps-client user) system-id app-id version-id body))

(defn admin-edit-app-docs
  [user system-id app-id body]
  (.adminEditAppDocs (get-apps-client user) system-id app-id body))

(defn admin-edit-app-version-docs
  [user system-id app-id version-id body]
  (.adminEditAppVersionDocs (get-apps-client user) system-id app-id version-id body))

(defn admin-add-app-docs
  [user system-id app-id body]
  (.adminAddAppDocs (get-apps-client user) system-id app-id body))

(defn admin-add-app-version-docs
  [user system-id app-id version-id body]
  (.adminAddAppVersionDocs (get-apps-client user) system-id app-id version-id body))

(defn list-app-permissions
  [user qualified-app-ids params]
  {:apps (.listAppPermissions (get-apps-client user) qualified-app-ids params)})

(defn share-apps
  [user sharing-requests]
  {:sharing (.shareApps (get-apps-client user) sharing-requests)})

(defn unshare-apps
  [user unsharing-requests]
  {:unsharing (.unshareApps (get-apps-client user) unsharing-requests)})

(defn list-job-permissions
  [user job-ids params]
  (jobs/list-job-permissions (get-apps-client user) user job-ids params))

(defn validate-job-sharing-request-body
  [user sharing-requests]
  (jobs/validate-job-sharing-request-body (get-apps-client user) user sharing-requests))

(defn share-jobs
  [user sharing-requests]
  (jobs/share-jobs (get-apps-client user) user sharing-requests))

(defn validate-job-unsharing-request-body
  [user unsharing-requests]
  (jobs/validate-job-unsharing-request-body (get-apps-client user) user unsharing-requests))

(defn unshare-jobs
  [user unsharing-requests]
  (jobs/unshare-jobs (get-apps-client user) user unsharing-requests))

(defn list-system-ids
  [user]
  {:de_system_id   de-system-id
   :all_system_ids (.listSystemIds (get-apps-client user))})

(defn admin-list-jobs-with-external-ids
  [user external-ids]
  (transaction (.adminListJobsWithExternalIds (get-apps-client user) external-ids)))
