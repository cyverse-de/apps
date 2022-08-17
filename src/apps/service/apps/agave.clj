(ns apps.service.apps.agave
  (:use [kameleon.uuids :only [uuidify]])
  (:require [clojure.string :as string]
            [apps.persistence.jobs :as jp]
            [apps.protocols]
            [apps.service.apps.agave.listings :as listings]
            [apps.service.apps.agave.pipelines :as pipelines]
            [apps.service.apps.agave.jobs :as agave-jobs]
            [apps.service.apps.agave.sharing :as sharing]
            [apps.service.apps.job-listings :as job-listings]
            [apps.service.apps.permissions :as app-permissions]
            [apps.service.apps.util :as apps-util]
            [apps.util.service :as service]))

(defn- reject-app-versions-request
  []
  (service/bad-request "Cannot list or modify HPC app versions with this service"))

(defn- reject-app-documentation-edit-request
  []
  (service/bad-request "Cannot edit documentation for HPC apps with this service"))

(def app-integration-rejection "Cannot add or modify HPC apps with this service")

(def integration-data-rejection "Cannot list or modify integration data for HPC apps with this service")

(def app-favorite-rejection "Cannot mark an HPC app as a favorite with this service.")

(def app-rating-rejection "Cannot rate an HPC app with this service.")

(def app-categorization-rejection "HPC apps cannot be placed in DE app categories.")

(def app-publishable-error "HPC apps may not be published via the DE at this time.")

(defn- reject-app-integration-request
  []
  (service/bad-request app-integration-rejection))

(defn- reject-app-favorite-request
  []
  (service/bad-request app-favorite-rejection))

(defn- reject-app-rating-request
  []
  (service/bad-request app-rating-rejection))

(defn- reject-categorization-request
  []
  (service/bad-request app-categorization-rejection))

(def ^:private supported-system-ids #{jp/agave-client-name})
(def ^:private validate-system-id (partial apps-util/validate-system-id supported-system-ids))
(def ^:private validate-system-ids (partial apps-util/validate-system-ids supported-system-ids))

(defn- empty-doc-map [app-id]
  {:app_id        app-id
   :documentation ""
   :references    []})

(deftype AgaveApps [agave user-has-access-token? user]
  apps.protocols.Apps

  (getUser [_]
    user)

  (getClientName [_]
    jp/agave-client-name)

  (getJobTypes [_]
    [jp/agave-job-type])

  (listSystemIds [_]
    (vec supported-system-ids))

  (supportsSystemId [_ system-id]
    (supported-system-ids system-id))

  (listAppCategories [_ {:keys [hpc]}]
    (when-not (and hpc (.equalsIgnoreCase hpc "false"))
      [(.hpcAppGroup agave)]))

  (listAppsInCategory [self system-id category-id params]
    (validate-system-id system-id)
    (if (apps-util/app-type-qualifies? self params)
      (listings/list-apps agave category-id params)
      (.emptyAppListing agave)))

  (listAppsUnderHierarchy [self root-iri attr params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/list-apps-with-ontology agave root-iri params false)
        (.emptyAppListing agave))))

  (adminListAppsUnderHierarchy [self ontology-version root-iri attr params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/list-apps-with-ontology agave root-iri params true)
        (.emptyAppListing agave))))

  ;; Since Agave doesn't list apps under ontology hierarchies, we'll use the ontology listing with communities for now.
  (listAppsInCommunity [self community-id params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/list-apps-with-ontology agave community-id params false)
        (.emptyAppListing agave))))

  (adminListAppsInCommunity [self community-id params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/list-apps-with-ontology agave community-id params true)
        (.emptyAppListing agave))))

  (searchApps [self search-term params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/search-apps agave search-term params false)
        (.emptyAppListing agave))))

  (listSingleApp [_ system-id app-id]
    (validate-system-id system-id)
    (listings/list-app agave app-id))

  (adminSearchApps [self search-term params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/search-apps agave search-term params true)
        (.emptyAppListing agave))))

  (canEditApps [_]
    false)

  (addApp [_ system-id _]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (addAppVersion [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (previewCommandLine [_ system-id _]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (validateDeletionRequest [_ deletion-request]
    (let [qualified-app-ids (:app_ids deletion-request)]
      (validate-system-ids (set (map :system_id qualified-app-ids)))
      (reject-app-integration-request)))

  (deleteApps [this deletion-request]
    (.validateDeletionRequest this deletion-request))

  (getAppJobView [this system-id app-id]
    (.getAppJobView this system-id app-id false))

  (getAppJobView [_ system-id app-id _]
    (validate-system-id system-id)
    (.getApp agave app-id))

  (getAppVersionJobView [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (deleteApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (deleteAppVersion [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (relabelApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (updateApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (copyApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (getAppDetails [_ system-id app-id admin?]
    (validate-system-id system-id)
    (listings/get-app-details agave app-id admin?))

  (removeAppFavorite [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-favorite-request))

  (addAppFavorite [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-favorite-request))

  (isAppPublishable [_ system-id app-id _]
    (validate-system-id system-id)
    [false app-publishable-error])

  (listToolsInUntrustedRegistries [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (usesToolsInUntrustedRegistries [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (createPublicationRequest [_ system-id app-id _]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (makeAppPublic [_ system-id app]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (deleteAppRating [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-rating-request))

  (rateApp [_ system-id app-id rating]
    (validate-system-id system-id)
    (reject-app-rating-request))

  (getAppTaskListing [_ system-id app-id]
    (validate-system-id system-id)
    (.listAppTasks agave app-id))

  (getAppToolListing [_ system-id app-id]
    (validate-system-id system-id)
    (.getAppToolListing agave app-id))

  (getAppUi [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (getAppInputIds [_ system-id app-id]
    (validate-system-id system-id)
    (.getAppInputIds agave app-id))

  (formatPipelineTasks [_ pipeline]
    (pipelines/format-pipeline-tasks agave pipeline))

  (listJobs [self params]
    (job-listings/list-jobs self user params))

  (listJobStats [self params]
    (job-listings/list-job-stats self user params))

  (adminListJobsWithExternalIds [_ external-ids]
    (job-listings/admin-list-jobs-with-external-ids external-ids))

  (loadAppTables [_ qualified-app-ids]
    (validate-system-ids (set (map :system_id qualified-app-ids)))
    (listings/load-app-tables agave (set (map :app_id qualified-app-ids))))

  (submitJob [this submission]
    (validate-system-id (:system_id submission))
    (agave-jobs/submit agave user submission))

  (submitJobStep [_ job-id submission]
    (agave-jobs/submit-step agave job-id submission))

  (translateJobStatus [self job-type status]
    (when (apps-util/supports-job-type? self job-type)
      (or (.translateJobStatus agave status) status)))

  (updateJobStatus [self job-step job status end-date]
    (when (apps-util/supports-job-type? self (:job_type job-step))
      (agave-jobs/update-job-status agave job-step job status end-date)))

  (getDefaultOutputName [_ io-map source-step]
    (agave-jobs/get-default-output-name agave io-map source-step))

  (getJobStepStatus [_ job-step]
    (agave-jobs/get-job-step-status agave job-step))

  (getJobStepHistory [_ {:keys [external_id]}]
    (.getJobHistory agave external_id))

  (prepareStepSubmission [_ job-id submission]
    (agave-jobs/prepare-step-submission agave job-id submission))

  (getParamDefinitions [_ system-id app-id]
    (validate-system-id system-id)
    (listings/get-param-definitions agave app-id))

  (stopJobStep [self {:keys [job_type external_id]}]
    (when (and (apps-util/supports-job-type? self job_type)
               (not (string/blank? external_id)))
      (.stopJob agave external_id)))

  (categorizeApps [_ {:keys [categories]}]
    (validate-system-ids (set (map :system_id categories)))
    (validate-system-ids (set (mapcat (fn [m] (map :system_id (:category_ids m))) categories)))
    (reject-categorization-request))

  (listAppPublicationRequests [_ _]
    [])

  (permanentlyDeleteApps [this req]
    (.validateDeletionRequest this req))

  (adminDeleteApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (adminUpdateApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (adminBlessApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (adminRemoveAppBlessing [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (getAdminAppCategories [_ _]
    [])

  (searchAdminAppCategories [_ _]
    [])

  (adminAddCategory [_ system-id _]
    (validate-system-id system-id)
    (reject-categorization-request))

  (adminDeleteCategory [_ system-id _]
    (validate-system-id system-id)
    (reject-categorization-request))

  (adminUpdateCategory [_ system-id _]
    (validate-system-id system-id)
    (reject-categorization-request))

  (getAppDocs [_ system-id app-id]
    (validate-system-id system-id)
    (empty-doc-map app-id))

  (getAppIntegrationData [_ system-id app-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (getToolIntegrationData [_ system-id tool-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (updateAppIntegrationData [_ system-id app-id integration-data-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (updateToolIntegrationData [_ system-id tool-id integration-data-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (ownerEditAppDocs [_ system-id app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (ownerAddAppDocs [_ system-id app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (adminEditAppDocs [_ system-id app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (adminAddAppDocs [_ system-id app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (listAppPermissions [_ qualified-app-ids _]
    (validate-system-ids (set (map :system_id qualified-app-ids)))
    (.listAppPermissions agave (map :app_id qualified-app-ids)))

  (shareApps [self sharing-requests]
    (app-permissions/process-app-sharing-requests self sharing-requests))

  (shareAppsWithSubject [self app-names sharee user-app-sharing-requests]
    (app-permissions/process-subject-app-sharing-requests self app-names sharee user-app-sharing-requests))

  (shareAppWithSubject [_ app-names sharee system-id app-id level]
    (validate-system-id system-id)
    (sharing/share-app-with-subject agave app-names sharee app-id level))

  (unshareApps [self unsharing-requests]
    (app-permissions/process-app-unsharing-requests self unsharing-requests))

  (unshareAppsWithSubject [self app-names sharee user-app-unsharing-requests]
    (app-permissions/process-subject-app-unsharing-requests self app-names sharee user-app-unsharing-requests))

  (unshareAppWithSubject [_ app-names sharee system-id app-id]
    (validate-system-id system-id)
    (sharing/unshare-app-with-subject agave app-names sharee app-id))

  (hasAppPermission [_ username system-id app-id required-level]
    (validate-system-id system-id)
    (.hasAppPermission agave username app-id required-level))

  (supportsJobSharing [_ _]
    true))
