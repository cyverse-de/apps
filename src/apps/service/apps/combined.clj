(ns apps.service.apps.combined
  "This namespace contains an implementation of apps.protocols.Apps that interacts with one
  or more other implementations. This implementation expects at most one the implementations that
  it interacts with to allow users to add new apps and edit existing ones. If this is not the case
  then the first app in the list that is capable of adding or editing apps wins."
  (:use [apps.util.assertions :only [assert-not-nil]])
  (:require [apps.persistence.jobs :as jp]
            [apps.protocols]
            [apps.service.apps.job-listings :as job-listings]
            [apps.service.apps.combined.job-view :as job-view]
            [apps.service.apps.combined.jobs :as combined-jobs]
            [apps.service.apps.combined.pipelines :as pipelines]
            [apps.service.apps.combined.util :as util]
            [apps.service.apps.permissions :as app-permissions]))

(deftype CombinedApps [clients user]
  apps.protocols.Apps

  (getUser [_]
    user)

  (getClientName [_]
    jp/combined-client-name)

  (getJobTypes [_]
    (mapcat #(.getJobTypes %) clients))

  (supportsSystemId [_ system-id]
    (some #(.supportsSystemId % system-id) clients))

  (listAppCategories [_ params]
    (mapcat #(.listAppCategories % params) clients))

  (listAppsInCategory [_ system-id category-id params]
    (assert-not-nil
     [:category-id category-id]
     (.listAppsInCategory (util/get-apps-client clients system-id) system-id category-id params)))

  (listAppsUnderHierarchy [_ root-iri attr params]
    (let [unpaged-params (dissoc params :limit :offset)]
      (->> (map #(.listAppsUnderHierarchy % root-iri attr unpaged-params) clients)
           (remove nil?)
           (util/combine-app-listings params))))

  (adminListAppsUnderHierarchy [_ ontology-version root-iri attr params]
    (let [unpaged-params (dissoc params :limit :offset)]
      (->> (map #(.adminListAppsUnderHierarchy % ontology-version root-iri attr unpaged-params) clients)
           (remove nil?)
           (util/combine-app-listings params))))

  (searchApps [_ search-term params]
    (->> (map #(.searchApps % search-term (select-keys params [:search])) clients)
         (remove nil?)
         (util/combine-app-listings params)))

  (adminSearchApps [_ search-term params]
    (->> (map #(.adminSearchApps % search-term (select-keys params [:search])) clients)
         (remove nil?)
         (util/combine-app-listings params)))

  (canEditApps [_]
    (some #(.canEditApps %) clients))

  (addApp [_ system-id app]
    (.addApp (util/get-apps-client clients system-id) system-id app))

  (previewCommandLine [_ system-id app]
    (.previewCommandLine (util/get-apps-client clients system-id) system-id app))

  (validateDeletionRequest [_ req]
    (let [requests-for-system (group-by :system_id (:app_ids req))]
      (doseq [[system-id qualified-app-ids] requests-for-system]
        (.validateDeletionRequest (util/get-apps-client clients system-id) (assoc req :app_ids qualified-app-ids)))))

  (deleteApps [this req]
    (.validateDeletionRequest this req)
    (let [requests-for-system (group-by :system_id (:app_ids req))]
      (doseq [[system-id qualified-app-ids] requests-for-system]
        (.deleteApps (util/get-apps-client clients system-id) (assoc req :app_ids qualified-app-ids)))))

  (getAppJobView [_ system-id app-id]
    (job-view/get-app system-id app-id clients))

  (getAppSubmissionInfo [_ system-id app-id]
    (job-view/get-app-submission-info system-id app-id clients))

  (deleteApp [_ system-id app-id]
    (.deleteApp (util/get-apps-client clients system-id) system-id app-id))

  (relabelApp [_ system-id app]
    (.relabelApp (util/get-apps-client clients system-id) system-id app))

  (updateApp [_ system-id app]
    (.updateApp (util/get-apps-client clients system-id) system-id app))

  (copyApp [_ system-id app-id]
    (.copyApp (util/get-apps-client clients system-id) system-id app-id))

  ;; FIXME: remove the admin flag when we have a better way to deal with administrative
  ;; privileges.
  (getAppDetails [_ system-id app-id admin?]
    (.getAppDetails (util/get-apps-client clients system-id) system-id app-id admin?))

  (removeAppFavorite [_ system-id app-id]
    (.removeAppFavorite (util/get-apps-client clients system-id) system-id app-id))

  (addAppFavorite [_ system-id app-id]
    (.addAppFavorite (util/get-apps-client clients system-id) system-id app-id))

  (isAppPublishable [_ system-id app-id]
    (.isAppPublishable (util/get-apps-client clients system-id) system-id app-id))

  (makeAppPublic [_ system-id app]
    (.makeAppPublic (util/get-apps-client clients system-id) system-id app))

  (deleteAppRating [_ system-id app-id]
    (.deleteAppRating (util/get-apps-client clients system-id) system-id app-id))

  (rateApp [_ system-id app-id rating]
    (.rateApp (util/get-apps-client clients system-id) system-id app-id rating))

  (getAppTaskListing [_ system-id app-id]
    (.getAppTaskListing (util/get-apps-client clients system-id) system-id app-id))

  (getAppToolListing [_ system-id app-id]
    (.getAppToolListing (util/get-apps-client clients system-id) system-id app-id))

  (getAppUi [_ system-id app-id]
    (.getAppUi (util/get-apps-client clients system-id) system-id app-id))

  (getAppInputIds [_ system-id app-id]
    (.getAppInputIds (util/get-apps-client clients system-id) system-id app-id))

  (addPipeline [self pipeline]
    (pipelines/format-pipeline self (.addPipeline (util/get-apps-client clients) pipeline)))

  (formatPipelineTasks [_ pipeline]
    (reduce (fn [acc client] (.formatPipelineTasks client acc)) pipeline clients))

  (updatePipeline [self pipeline]
    (pipelines/format-pipeline self (.updatePipeline (util/get-apps-client clients) pipeline)))

  (copyPipeline [self app-id]
    (pipelines/format-pipeline self (.copyPipeline (util/get-apps-client clients) app-id)))

  (editPipeline [self app-id]
    (pipelines/format-pipeline self (.editPipeline (util/get-apps-client clients) app-id)))

  (listJobs [self params]
    (job-listings/list-jobs self user params))

  (loadAppTables [_ qualified-app-ids]
    (doall (mapcat (fn [[system-id qual-ids]]
                     (.loadAppTables (util/get-apps-client clients system-id) qual-ids))
                   (group-by :system_id qualified-app-ids))))

  (submitJob [self submission]
    (if-let [apps-client (util/apps-client-for-job submission clients)]
      (.submitJob apps-client submission)
      (job-listings/list-job self (combined-jobs/submit user clients submission))))

  (translateJobStatus [_ job-type status]
    (->> (map #(.translateJobStatus % job-type status) clients)
         (remove nil?)
         (first)))

  (updateJobStatus [self job-step job status end-date]
    (combined-jobs/update-job-status self clients job-step job status end-date))

  (getDefaultOutputName [_ io-map source-step]
    (.getDefaultOutputName (util/apps-client-for-app-step clients source-step) io-map source-step))

  (getJobStepStatus [_ job-step]
    (.getJobStepStatus (util/apps-client-for-job-step clients job-step) job-step))

  (buildNextStepSubmission [self job-step job]
    (combined-jobs/build-next-step-submission self clients job-step job))

  (getParamDefinitions [_ system-id app-id]
    (.getParamDefinitions (util/get-apps-client clients system-id) system-id app-id))

  (stopJobStep [_ job-step]
    (dorun (map #(.stopJobStep % job-step) clients)))

  (categorizeApps [_ {:keys [categories]}]
    (let [requests-by-system-id (group-by :system_id categories)]
      (dorun (map (fn [[system-id categories]]
                    (.categorizeApps (util/get-apps-client clients system-id) {:categories categories}))
                  requests-by-system-id))))

  (permanentlyDeleteApps [this req]
    (.validateDeletionRequest this req)
    (let [requests-for-system (group-by :system_id (:app_ids req))]
      (doseq [[system-id qualified-app-ids] requests-for-system]
        (.permanentlyDeleteApps (util/get-apps-client clients system-id) (assoc req :app_ids qualified-app-ids)))))

  (adminDeleteApp [_ system-id app-id]
    (.adminDeleteApp (util/get-apps-client clients system-id) system-id app-id))

  (adminUpdateApp [_ system-id body]
    (.adminUpdateApp (util/get-apps-client clients system-id) system-id body))

  (getAdminAppCategories [_ params]
    (mapcat #(.getAdminAppCategories % params) clients))

  (adminAddCategory [_ system-id body]
    (.adminAddCategory (util/get-apps-client clients system-id) system-id body))

  (adminDeleteCategory [_ system-id category-id]
    (.adminDeleteCategory (util/get-apps-client clients system-id) system-id category-id))

  (adminUpdateCategory [_ system-id body]
    (.adminUpdateCategory (util/get-apps-client clients) system-id body))

  (getAppDocs [_ system-id app-id]
    (.getAppDocs (util/get-apps-client clients system-id) system-id app-id))

  (getAppIntegrationData [_ system-id app-id]
    (.getAppIntegrationData (util/get-apps-client clients system-id) system-id app-id))

  (getToolIntegrationData [_ system-id tool-id]
    (.getToolIntegrationData (util/get-apps-client clients system-id) system-id tool-id))

  (updateAppIntegrationData [_ system-id app-id integration-data-id]
    (.updateAppIntegrationData (util/get-apps-client clients system-id) system-id app-id integration-data-id))

  (updateToolIntegrationData [_ system-id tool-id integration-data-id]
    (.updateToolIntegrationData (util/get-apps-client clients system-id) system-id tool-id integration-data-id))

  (ownerEditAppDocs [_ system-id app-id body]
    (.ownerEditAppDocs (util/get-apps-client clients system-id) system-id app-id body))

  (ownerAddAppDocs [_ system-id app-id body]
    (.ownerAddAppDocs (util/get-apps-client clients system-id) system-id app-id body))

  (adminEditAppDocs [_ system-id app-id body]
    (.adminEditAppDocs (util/get-apps-client clients system-id) system-id app-id body))

  (adminAddAppDocs [_ system-id app-id body]
    (.adminAddAppDocs (util/get-apps-client clients system-id) app-id body))

  (listAppPermissions [_ qualified-app-ids]
    (->> (group-by :system_id qualified-app-ids)
         (mapcat (fn [[system-id qualified-app-ids-for-system]]
                   (let [client (util/get-apps-client clients system-id)]
                     (.listAppPermissions client qualified-app-ids-for-system))))
         doall))

  (shareApps [self sharing-requests]
    (app-permissions/process-app-sharing-requests self sharing-requests))

  (shareAppsWithUser [self app-names sharee user-app-sharing-requests]
    (app-permissions/process-user-app-sharing-requests self app-names sharee user-app-sharing-requests))

  (shareAppWithUser [_ app-names sharee system-id app-id level]
    (.shareAppWithUser (util/get-apps-client clients system-id) app-names sharee system-id app-id level))

  (unshareApps [self unsharing-requests]
    (app-permissions/process-app-unsharing-requests self unsharing-requests))

  (unshareAppsWithUser [self app-names sharee app-unsharing-requests]
    (app-permissions/process-user-app-unsharing-requests self app-names sharee app-unsharing-requests))

  (unshareAppWithUser [self app-names sharee system-id app-id]
    (.unshareAppWithUser (util/get-apps-client clients system-id) app-names sharee system-id app-id))

  (hasAppPermission [_ username system-id app-id required-level]
    (.hasAppPermission (util/get-apps-client clients system-id) username system-id app-id required-level))

  (supportsJobSharing [_ job-step]
    (.supportsJobSharing (util/apps-client-for-job-step clients job-step) job-step)))
