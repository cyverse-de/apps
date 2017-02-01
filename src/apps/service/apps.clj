(ns apps.service.apps
  (:use [apps.service.oauth :only [authorization-uri has-access-token]]
        [kameleon.uuids :only [uuidify]]
        [korma.db :only [transaction]]
        [slingshot.slingshot :only [try+ throw+]]
        [service-logging.thread-context :only [with-logging-context]])
  (:require [clojure.tools.logging :as log]
            [mescal.de :as agave]
            [apps.clients.notifications :as cn]
            [apps.persistence.jobs :as jp]
            [apps.persistence.oauth :as op]
            [apps.protocols]
            [apps.service.apps.agave]
            [apps.service.apps.combined]
            [apps.service.apps.de]
            [apps.service.apps.jobs :as jobs]
            [apps.user :as user]
            [apps.util.config :as config]
            [apps.util.json :as json-util]
            [service-logging.thread-context :as tc]))

(defn- authorization-redirect
  [server-info username state-info]
  (throw+ {:type     :clojure-commons.exception/temporary-redirect
           :location (authorization-uri server-info username state-info)}))

(defn- get-access-token
  [{:keys [api-name] :as server-info} state-info username]
  (if-let [token-info (op/get-access-token api-name username)]
    (assoc (merge server-info token-info)
      :token-callback  (partial op/store-access-token api-name username)
      :reauth-callback (partial authorization-redirect server-info username state-info))
    (authorization-redirect server-info username state-info)))

(defn- get-agave-client
  [state-info username]
  (let [server-info (config/agave-oauth-settings)]
    (agave/de-agave-client-v2
     (config/agave-base-url)
     (config/agave-storage-system)
     (partial get-access-token (config/agave-oauth-settings) state-info username)
     (config/agave-jobs-enabled)
     :timeout  (config/agave-read-timeout)
     :page-len (config/agave-page-length))))

(defn- get-agave-apps-client
  [state-info {:keys [username] :as user}]
  (apps.service.apps.agave.AgaveApps.
   (get-agave-client state-info username)
   (partial has-access-token (config/agave-oauth-settings) username)
   user))

(defn- get-apps-client-list
  [user state-info]
  (vector (apps.service.apps.de.DeApps. user)
          (when (and user (config/agave-enabled))
            (get-agave-apps-client state-info user))))

(defn- get-apps-client
  ([user]
     (get-apps-client user ""))
  ([user state-info]
     (apps.service.apps.combined.CombinedApps.
      (remove nil? (get-apps-client-list user state-info))
      user)))

(defn- get-apps-client-for-username
  ([username]
     (get-apps-client-for-username username ""))
  ([username state-info]
     (get-apps-client (user/load-user-as-user username username) state-info)))

(defn get-app-categories
  [user params]
  (let [client (get-apps-client user "type=apps")]
    {:categories (transaction (.listAppCategories client params))}))

(defn list-apps-in-category
  [user category-id params]
  (let [state-info (str "type=apps&app-category=" category-id)
        client     (get-apps-client user state-info)]
    (.listAppsInCategory client category-id params)))

(defn list-apps-under-hierarchy
  [user root-iri attr params]
  (.listAppsUnderHierarchy (get-apps-client user) root-iri attr params))

(defn admin-list-apps-under-hierarchy
  [user ontology-version root-iri attr params]
  (.adminListAppsUnderHierarchy (get-apps-client user) ontology-version root-iri attr params))

(defn search-apps
  [user {:keys [search] :as params}]
  (.searchApps (get-apps-client user) search params))

(defn admin-search-apps
  [user {:keys [search] :as params}]
  (.adminSearchApps (get-apps-client user) search params))

(defn add-app
  [user system-id app]
  (.addApp (get-apps-client user) system-id app))

(defn preview-command-line
  [user system-id app]
  (.previewCommandLine (get-apps-client user) system-id app))

(defn delete-apps
  [user deletion-request]
  (.deleteApps (get-apps-client user) deletion-request))

(defn get-app-job-view
  [user system-id app-id]
  (.getAppJobView (get-apps-client user) system-id app-id))

(defn delete-app
  [user system-id app-id]
  (.deleteApp (get-apps-client user) system-id app-id))

(defn relabel-app
  [user system-id app]
  (.relabelApp (get-apps-client user) system-id app))

(defn update-app
  [user system-id app]
  (.updateApp (get-apps-client user) system-id app))

(defn copy-app
  [user system-id app-id]
  (.copyApp (get-apps-client user) system-id app-id))

(defn get-app-details
  [user system-id app-id]
  (.getAppDetails (get-apps-client user) system-id app-id false))

(defn admin-get-app-details
  [user system-id app-id]
  (.getAppDetails (get-apps-client user) system-id app-id true))

(defn remove-app-favorite
  [user system-id app-id]
  (.removeAppFavorite (get-apps-client user) system-id app-id))

(defn add-app-favorite
  [user system-id app-id]
  (.addAppFavorite (get-apps-client user) system-id app-id))

(defn app-publishable?
  [user system-id app-id]
  {:publishable (.isAppPublishable (get-apps-client user) system-id app-id)})

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

(defn get-app-tool-listing
  [user system-id app-id]
  (.getAppToolListing (get-apps-client user) system-id app-id))

(defn get-app-ui
  [user system-id app-id]
  (.getAppUi (get-apps-client user) system-id app-id))

(defn add-pipeline
  [user pipeline]
  (.addPipeline (get-apps-client user) pipeline))

(defn update-pipeline
  [user pipeline]
  (.updatePipeline (get-apps-client user) pipeline))

(defn copy-pipeline
  [user app-id]
  (.copyPipeline (get-apps-client user) app-id))

(defn edit-pipeline
  [user app-id]
  (.editPipeline (get-apps-client user) app-id))

(defn list-jobs
  [user params]
  (.listJobs (get-apps-client user) params))

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
  ([external-id status end-date]
     (let [{job-id :job_id} (jobs/get-unique-job-step external-id)]
       (update-job-status job-id external-id status end-date)))
  ([job-id external-id status end-date]
     (transaction
      (let [job-step (jobs/lock-job-step job-id external-id)
            job      (jobs/lock-job job-id)
            batch    (when-let [parent-id (:parent_id job)] (jobs/lock-job parent-id))]
        (-> (get-apps-client-for-username (:username job))
            (jobs/update-job-status job-step job batch status end-date))))))

(def logging-context-map (ref {}))

(defn set-logging-context!
  "Sets the logging ThreadContext for the sync-job-statuses function, which is
  run in a new thread as a task."
  [cm]
  (dosync (ref-set logging-context-map cm)))

(defn- sync-job-status
  [{:keys [username id] :as job}]
  (with-logging-context {}
    (try+
      (log/info "synchronizing the job status for" id)
      (transaction (jobs/sync-job-status (get-apps-client-for-username username) job))
      (catch Object _
        (log/error (:throwable &throw-context) "unable to sync the job status for job" id)))))

(defn sync-job-statuses
  []
  (tc/with-logging-context @logging-context-map
    (try+
      (log/info "synchronizing job statuses")
      (dorun (map sync-job-status (jp/list-incomplete-jobs)))
      (catch Object _
        (log/error (:throwable &throw-context)
                   "error while obtaining the list of jobs to synchronize."))
      (finally
        (log/info "done synchronizing job statuses")))))

(defn update-job
  [user job-id body]
  (jobs/update-job user job-id body))

(defn delete-job
  [user job-id]
  (jobs/delete-job user job-id)
  nil)

(defn delete-jobs
  [user body]
  (jobs/delete-jobs user (:analyses body))
  nil)

(defn get-parameter-values
  [user job-id]
  (jobs/get-parameter-values (get-apps-client user) user job-id))

(defn get-job-relaunch-info
  [user job-id]
  (jobs/get-job-relaunch-info (get-apps-client user) user job-id))

(defn stop-job
  [user job-id]
  (jobs/stop-job (get-apps-client user) user job-id)
  {:id job-id})

(defn list-job-steps
  [user job-id]
  (jobs/list-job-steps user job-id))

(defn categorize-apps
  [user body]
  (transaction (.categorizeApps (get-apps-client user) body)))

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

(defn get-admin-app-categories
  [user params]
  {:categories (.getAdminAppCategories (get-apps-client user) params)})

(defn admin-add-category
  [user system-id body]
  (let [apps-client (get-apps-client user)]
    (.listAppsInCategory apps-client (.adminAddCategory apps-client system-id body) {})))

(defn admin-delete-category
  [user system-id category-id]
  (.adminDeleteCategory (get-apps-client user) system-id category-id))

(defn admin-update-category
  [user system-id {:keys [id] :as body}]
  (let [apps-client (get-apps-client user)]
    (.adminUpdateCategory apps-client system-id body)
    (.listAppsInCategory apps-client id {})))

(defn get-app-docs
  [user system-id app-id]
  (.getAppDocs (get-apps-client user) system-id app-id))

(defn get-app-integration-data
  [user system-id app-id]
  (.getAppIntegrationData (get-apps-client user) system-id app-id))

(defn get-tool-integration-data
  [user tool-id]
  (.getToolIntegrationData (get-apps-client user) tool-id))

(defn update-app-integration-data
  ([user app-id integration-data-id]
   (.updateAppIntegrationData (get-apps-client user) app-id integration-data-id))
  ([user system-id app-id integration-data-id]
   (.updateAppIntegrationData (get-apps-client user) system-id app-id integration-data-id)))

(defn update-tool-integration-data
  [user tool-id integration-data-id]
  (.updateToolIntegrationData (get-apps-client user) tool-id integration-data-id))

(defn owner-edit-app-docs
  [user app-id body]
  (.ownerEditAppDocs (get-apps-client user) app-id body))

(defn owner-add-app-docs
  [user app-id body]
  (.ownerAddAppDocs (get-apps-client user) app-id body))

(defn admin-edit-app-docs
  ([user app-id body]
   (.adminEditAppDocs (get-apps-client user) app-id body))
  ([user system-id app-id body]
   (.adminEditAppDocs (get-apps-client user) system-id app-id body)))

(defn admin-add-app-docs
  ([user app-id body]
   (.adminAddAppDocs (get-apps-client user) app-id body))
  ([user system-id app-id body]
   (.adminAddAppDocs (get-apps-client user) system-id app-id body)))

(defn list-app-permissions
  [user qualified-app-ids]
  {:apps (.listAppPermissions (get-apps-client user) qualified-app-ids)})

(defn share-apps
  [user sharing-requests]
  {:sharing (.shareApps (get-apps-client user) sharing-requests)})

(defn unshare-apps
  [user unsharing-requests]
  {:unsharing (.unshareApps (get-apps-client user) unsharing-requests)})

(defn list-job-permissions
  [user job-ids]
  (jobs/list-job-permissions (get-apps-client user) user job-ids))

(defn share-jobs
  [user sharing-requests]
  {:sharing (jobs/share-jobs (get-apps-client user) user sharing-requests)})

(defn unshare-jobs
  [user unsharing-requests]
  {:unsharing (jobs/unshare-jobs (get-apps-client user) user unsharing-requests)})
