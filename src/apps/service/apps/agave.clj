(ns apps.service.apps.agave
  (:use [kameleon.uuids :only [uuidify]])
  (:require [clojure.string :as string]
            [apps.persistence.jobs :as jp]
            [apps.protocols]
            [apps.service.apps.agave.listings :as listings]
            [apps.service.apps.agave.pipelines :as pipelines]
            [apps.service.apps.agave.jobs :as agave-jobs]
            [apps.service.apps.job-listings :as job-listings]
            [apps.service.apps.permissions :as app-permissions]
            [apps.service.apps.util :as apps-util]
            [apps.service.util :as util]
            [apps.util.service :as service]))

(defn- reject-app-documentation-edit-request
  []
  (service/bad-request "Cannot edit documentation for HPC apps with this service"))

(def app-integration-rejection "Cannot add or modify HPC apps with this service")

(def app-permission-rejection "Cannot list or modify the permissions of HPC apps with this service")

(def analysis-permission-rejection "Cannot list or modify the permissions of HPC analyses with this service")

(def integration-data-rejection "Cannot list or modify integration data for HPC apps with this service")

(defn- reject-app-permission-request
  []
  (service/bad-request app-permission-rejection))

(defn- reject-app-integration-request
  []
  (service/bad-request app-integration-rejection))

(def ^:private supported-system-ids #{jp/agave-client-name})
(def ^:private validate-system-id (partial apps-util/validate-system-id supported-system-ids))
(def ^:private validate-system-ids (partial apps-util/validate-system-ids supported-system-ids))

(deftype AgaveApps [agave user-has-access-token? user]
  apps.protocols.Apps

  (getUser [_]
    user)

  (getClientName [_]
    jp/agave-client-name)

  (getJobTypes [_]
    [jp/agave-job-type])

  (supportsSystemId [_ system-id]
    (supported-system-ids system-id))

  (listAppCategories [_ {:keys [hpc]}]
    (when-not (and hpc (.equalsIgnoreCase hpc "false"))
      [(.hpcAppGroup agave)]))

  (hasCategory [_ category-id]
    (= category-id (uuidify (:id (.hpcAppGroup agave)))))

  (listAppsInCategory [_ category-id params]
    (when (= category-id (uuidify (:id (.hpcAppGroup agave))))
      (listings/list-apps agave category-id params)))

  (listAppsUnderHierarchy [_ root-iri attr params]
    (when (user-has-access-token?)
      (listings/list-apps-with-ontology agave root-iri params)))

  (adminListAppsUnderHierarchy [_ ontology-version root-iri attr params]
    (when (user-has-access-token?)
      (listings/list-apps-with-ontology agave root-iri params)))

  (searchApps [_ search-term params]
    (when (user-has-access-token?)
      (listings/search-apps agave search-term params)))

  (canEditApps [_]
    false)

  (addApp [_ system-id _]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (previewCommandLine [_ system-id _]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (validateDeletionRequest [_ deletion-request]
    (let [qualified-app-ids (:app_ids deletion-request)]
      (validate-system-ids (set (map :system_id qualified-app-ids)))
      (reject-app-integration-request)))

  (deleteApps [this deletion-request]
    (.validateDeletionRequest this deletion-request))

  (getAppJobView [_ app-id]
    (when-not (util/uuid? app-id)
      (.getApp agave app-id)))

  (getAppJobView [_ system-id app-id]
    (validate-system-id system-id)
    (.getApp agave app-id))

  (deleteApp [_ app-id]
    (when-not (util/uuid? app-id)
      (reject-app-integration-request)))

  (deleteApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (relabelApp [_ app-id]
    (when-not (util/uuid? app-id)
      (reject-app-integration-request)))

  (relabelApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (updateApp [_ app-id]
    (when-not (util/uuid? app-id)
      (reject-app-integration-request)))

  (updateApp [_ system-id app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  ;; FIXME: remove the third parameter when we can.
  (getAppDetails [_ app-id _]
    (when-not (util/uuid? app-id)
      (.getAppDetails agave app-id)))

  ;; FIXME: remove the fourth parameter when we can.
  (getAppDetails [_ system-id app-id _]
    (validate-system-id system-id)
    (.getAppDetails agave app-id))

  (isAppPublishable [_ app-id]
    (when-not (util/uuid? app-id)
      false))

  (isAppPublishable [_ system-id app-id]
    false)

  (getAppTaskListing [_ app-id]
    (when-not (util/uuid? app-id)
      (.listAppTasks agave app-id)))

  (getAppTaskListing [_ system-id app-id]
    (validate-system-id system-id)
    (.listAppTasks agave app-id))

  (getAppToolListing [_ app-id]
    (when-not (util/uuid? app-id)
      (.getAppToolListing agave app-id)))

  (getAppToolListing [_ system-id app-id]
    (validate-system-id system-id)
    (.getAppToolListing agave app-id))

  (getAppInputIds [_ app-id]
    (when-not (util/uuid? app-id)
      (.getAppInputIds agave app-id)))

  (getAppInputIds [_ system-id app-id]
    (validate-system-id system-id)
    (.getAppInputIds agave app-id))

  (formatPipelineTasks [_ pipeline]
    (pipelines/format-pipeline-tasks agave pipeline))

  (listJobs [self params]
    (job-listings/list-jobs self user params))

  ;; TODO: Determine how this should be refactored now that we have system IDs. If I remember correctly,
  ;; this method is used during job listings. If that's the case, we can filter apps by execution system
  ;; easily enough.
  (loadAppTables [_ app-ids]
    (let [agave-app-ids (remove util/uuid? app-ids)]
      (if (and (seq agave-app-ids) (user-has-access-token?))
        (listings/load-app-tables agave agave-app-ids)
        [])))

  (submitJob [this submission]
    (when-not (util/uuid? (:app_id submission))
      (agave-jobs/submit agave user submission)))

  (submitJob [this system-id submission]
    (validate-system-id system-id)
    (agave-jobs/submit agave user submission))

  (submitJobStep [_ job-id submission]
    (agave-jobs/submit-step agave job-id submission))

  (translateJobStatus [self job-type status]
    (when (apps-util/supports-job-type? self job-type)
      (or (.translateJobStatus agave status) status)))

  (updateJobStatus [self job-step job status end-date]
    (when (apps-util/supports-job-type? self (:job-type job-step))
      (agave-jobs/update-job-status agave job-step job status end-date)))

  (getDefaultOutputName [_ io-map source-step]
    (agave-jobs/get-default-output-name agave io-map source-step))

  (getJobStepStatus [_ job-step]
    (agave-jobs/get-job-step-status agave job-step))

  (prepareStepSubmission [_ job-id submission]
    (agave-jobs/prepare-step-submission agave job-id submission))

  (getParamDefinitions [_ app-id]
    (listings/get-param-definitions agave app-id))

  (getParamDefinitions [_ system-id app-id]
    (validate-system-id system-id)
    (listings/get-param-definitions agave app-id))

  (stopJobStep [self {:keys [job-type external-id]}]
    (when (and (apps-util/supports-job-type? self job-type)
               (not (string/blank? external-id)))
      (.stopJob agave external-id)))

  (getAppDocs [_ app-id]
    (when-not (util/uuid? app-id)
      {:app_id        app-id
       :documentation ""
       :references    []}))

  (getAppDocs [_ system-id app-id]
    (validate-system-id system-id)
    {:app_id        app-id
     :documentation ""
     :references    []})

  (getAppIntegrationData [_ app-id]
    (when-not (util/uuid? app-id)
      (service/bad-request integration-data-rejection)))

  (getAppIntegrationData [_ system-id app-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (getToolIntegrationData [_ tool-id]
    (when-not (util/uuid? tool-id)
      (service/bad-request integration-data-rejection)))

  (getToolIntegrationData [_ system-id tool-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (updateAppIntegrationData [_ app-id integration-data-id]
    (when-not (util/uuid? app-id)
      (service/bad-request integration-data-rejection)))

  (updateAppIntegrationData [_ system-id app-id integration-data-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (updateToolIntegrationData [_ tool-id integration-data-id]
    (when-not (util/uuid? tool-id)
      (service/bad-request integration-data-rejection)))

  (updateToolIntegrationData [_ system-id tool-id integration-data-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (ownerEditAppDocs [_ app-id _]
    (when-not (util/uuid? app-id)
      (reject-app-documentation-edit-request)))

  (ownerEditAppDocs [_ system-id app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (ownerAddAppDocs [_ app-id _]
    (when-not (util/uuid? app-id)
      (reject-app-documentation-edit-request)))

  (ownerAddAppDocs [_ system-id app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (adminEditAppDocs [_ app-id _]
    (when-not (util/uuid? app-id)
      (reject-app-documentation-edit-request)))

  (adminEditAppDocs [_ system-id app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (adminAddAppDocs [_ app-id _]
    (when-not (util/uuid? app-id)
      (reject-app-documentation-edit-request)))

  (adminAddAppDocs [_ system-id app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  ;; TODO: this will have to be changed when the corresponding endpoint is changed.
  (listAppPermissions [_ app-ids]
    (when (and (user-has-access-token?)
               (some (complement util/uuid?) app-ids))
      (reject-app-permission-request)))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (shareApps [self sharing-requests]
    (app-permissions/process-app-sharing-requests self sharing-requests))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (shareAppsWithUser [self app-names sharee user-app-sharing-requests]
    (app-permissions/process-user-app-sharing-requests self app-names sharee user-app-sharing-requests))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (shareAppWithUser [_ app-names _ app-id level]
    (when (and (user-has-access-token?)
               (not (util/uuid? app-id)))
      (let [category (.hpcAppGroup agave)]
        (app-permissions/app-sharing-failure app-names app-id level category category app-permission-rejection))))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (unshareApps [self unsharing-requests]
    (app-permissions/process-app-unsharing-requests self unsharing-requests))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (unshareAppsWithUser [self app-names sharee app-ids]
    (app-permissions/process-user-app-unsharing-requests self app-names sharee app-ids))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (unshareAppWithUser [_ app-names _ app-id]
    (when (and (user-has-access-token?)
               (not (util/uuid? app-id)))
      (let [category (.hpcAppGroup agave)]
        (app-permissions/app-unsharing-failure app-names app-id category app-permission-rejection))))

  (hasAppPermission [_ username app-id required-level]
    (when (and (user-has-access-token?)
               (not (util/uuid? app-id)))
      false))

  (hasAppPermission [_ username system-id app-id required-level]
    (validate-system-id system-id)
    false)

  (supportsJobSharing [_ _]
    false))
