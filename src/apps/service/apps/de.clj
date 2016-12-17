(ns apps.service.apps.de
  (:require [clojure.string :as string]
            [apps.clients.jex :as jex]
            [apps.persistence.app-metadata :as ap]
            [apps.persistence.jobs :as jp]
            [apps.protocols]
            [apps.service.apps.de.admin :as app-admin]
            [apps.service.apps.de.categorization :as app-categorization]
            [apps.service.apps.de.docs :as docs]
            [apps.service.apps.de.edit :as edit]
            [apps.service.apps.de.jobs :as de-jobs]
            [apps.service.apps.de.job-view :as job-view]
            [apps.service.apps.de.listings :as listings]
            [apps.service.apps.de.metadata :as app-metadata]
            [apps.service.apps.de.permissions :as perms]
            [apps.service.apps.de.pipeline-edit :as pipeline-edit]
            [apps.service.apps.de.sharing :as sharing]
            [apps.service.apps.de.validation :as app-validation]
            [apps.service.apps.job-listings :as job-listings]
            [apps.service.apps.permissions :as app-permissions]
            [apps.service.apps.util :as apps-util]
            [apps.service.integration-data :as integration-data]
            [apps.service.util :as util :refer [uuidify]]))

(def ^:private supported-system-ids #{jp/de-client-name})
(def ^:private validate-system-id (partial apps-util/validate-system-id supported-system-ids))
(def ^:private validate-system-ids (partial apps-util/validate-system-ids supported-system-ids))

(deftype DeApps [user]
  apps.protocols.Apps

  (getUser [_]
    user)

  (getClientName [_]
    jp/de-client-name)

  (getJobTypes [_]
    [jp/de-job-type])

  (supportsSystemId [_ system-id]
    (supported-system-ids system-id))

  (listAppCategories [_ params]
    (listings/get-app-groups user params))

  (hasCategory [_ category-id]
    (listings/has-category category-id))

  (listAppsInCategory [_ category-id params]
    (listings/list-apps-in-group user category-id params))

  (listAppsUnderHierarchy [_ root-iri attr params]
    (listings/list-apps-under-hierarchy user root-iri attr params))

  (adminListAppsUnderHierarchy [_ ontology-version root-iri attr params]
    (listings/list-apps-under-hierarchy user ontology-version root-iri attr params true))

  (searchApps [_ _ params]
    (listings/list-apps user params false))

  (adminSearchApps [_ _ params]
    (listings/list-apps user params true))

  (canEditApps [_]
    true)

  (addApp [_ system-id app]
    (validate-system-id system-id)
    (edit/add-app user app))

  (previewCommandLine [_ system-id app]
    (validate-system-id system-id)
    (app-metadata/preview-command-line app))

  (validateDeletionRequest [_ req]
    (let [qualified-app-ids (:app_ids req)]
      (validate-system-ids (set (map :system_id qualified-app-ids)))
      (app-metadata/validate-deletion-request user req)))

  (deleteApps [this req]
    (.validateDeletionRequest this req)
    (app-metadata/delete-apps user req))

  (getAppJobView [_ system-id app-id]
    (validate-system-id system-id)
    (job-view/get-app (uuidify app-id)))

  (deleteApp [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/delete-app user (uuidify app-id)))

  (relabelApp [_ system-id app]
    (validate-system-id system-id)
    (edit/relabel-app user (update app :id uuidify)))

  (updateApp [_ system-id app]
    (validate-system-id system-id)
    (edit/update-app user (update app :id uuidify)))

  (copyApp [_ app-id]
    (when (util/uuid? app-id)
      (edit/copy-app user app-id)))

  (copyApp [_ system-id app-id]
    (validate-system-id system-id)
    (edit/copy-app user (uuidify app-id)))

  ;; FIXME: remove the admin flag when we have a better way to do this.
  (getAppDetails [_ app-id admin?]
    (when (util/uuid? app-id)
      (listings/get-app-details user (uuidify app-id) admin?)))

  ;; FIXME: remove the admin flag when we have a better way to do this.
  (getAppDetails [_ system-id app-id admin?]
    (validate-system-id system-id)
    (listings/get-app-details user (uuidify app-id) admin?))

  (removeAppFavorite [_ app-id]
    (when (util/uuid? app-id)
      (app-metadata/remove-app-favorite user (uuidify app-id))))

  (removeAppFavorite [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/remove-app-favorite user (uuidify app-id)))

  (addAppFavorite [_ app-id]
    (when (util/uuid? app-id)
      (app-metadata/add-app-favorite user (uuidify app-id))))

  (addAppFavorite [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/add-app-favorite user (uuidify app-id)))

  (isAppPublishable [_ app-id]
    (when (util/uuid? app-id)
      (first (app-validation/app-publishable? user (uuidify app-id)))))

  (isAppPublishable [_ system-id app-id]
    (validate-system-id system-id)
    (first (app-validation/app-publishable? user (uuidify app-id))))

  (makeAppPublic [_ app]
    (when (util/uuid? (:id app))
      (app-metadata/make-app-public user (update app :id uuidify))))

  (makeAppPublic [_ system-id app]
    (validate-system-id system-id)
    (app-metadata/make-app-public user (update app :id uuidify)))

  (deleteAppRating [_ app-id]
    (when (util/uuid? app-id)
      (app-metadata/delete-app-rating user (uuidify app-id))))

  (deleteAppRating [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/delete-app-rating user (uuidify app-id)))

  (rateApp [_ app-id rating]
    (when (util/uuid? app-id)
      (app-metadata/rate-app user (uuidify app-id) rating)))

  (rateApp [_ system-id app-id rating]
    (validate-system-id system-id)
    (app-metadata/rate-app user (uuidify app-id) rating))

  (getAppTaskListing [_ app-id]
    (when (util/uuid? app-id)
      (listings/get-app-task-listing user (uuidify app-id))))

  (getAppTaskListing [_ system-id app-id]
    (validate-system-id system-id)
    (listings/get-app-task-listing user (uuidify app-id)))

  (getAppToolListing [_ app-id]
    (when (util/uuid? app-id)
      (listings/get-app-tool-listing user (uuidify app-id))))

  (getAppToolListing [_ system-id app-id]
    (validate-system-id system-id)
    (listings/get-app-tool-listing user (uuidify app-id)))

  (getAppUi [_ app-id]
    (when (util/uuid? app-id)
      (edit/get-app-ui user (uuidify app-id))))

  (getAppUi [_ system-id app-id]
    (validate-system-id system-id)
    (edit/get-app-ui user (uuidify app-id)))

  (getAppInputIds [_ app-id]
    (when (util/uuid? app-id)
      (listings/get-app-input-ids (uuidify app-id))))

  (getAppInputIds [_ system-id app-id]
    (validate-system-id system-id)
    (listings/get-app-input-ids (uuidify app-id)))

  (addPipeline [_ pipeline]
    (pipeline-edit/add-pipeline user pipeline))

  (formatPipelineTasks [_ pipeline]
    pipeline)

  (updatePipeline [_ pipeline]
    (pipeline-edit/update-pipeline user pipeline))

  (copyPipeline [_ app-id]
    (pipeline-edit/copy-pipeline user app-id))

  (editPipeline [_ app-id]
    (pipeline-edit/edit-pipeline user app-id))

  (listJobs [self params]
    (job-listings/list-jobs self user params))

  ;; TODO: Determine how this should be refactored now that we have system IDs. If I remember correctly,
  ;; this method is used during job listings. If that's the case, we can filter apps by execution system
  ;; easily enough.
  (loadAppTables [_ app-ids]
    (->> (filter util/uuid? app-ids)
         (ap/load-app-details)
         (map (juxt (comp str :id) identity))
         (into {})
         (vector)))

  (submitJob [this submission]
    (validate-system-id (:system_id submission))
    (de-jobs/submit user (update-in submission [:app_id] uuidify)))

  (submitJobStep [_ _ submission]
    (de-jobs/submit-step user (update-in submission [:app_id] uuidify)))

  (translateJobStatus [self job-type status]
    (when (apps-util/supports-job-type? self job-type)
      status))

  (updateJobStatus [self job-step job status end-date]
    (when (apps-util/supports-job-type? self (:job_type job-step))
      (de-jobs/update-job-status job-step job status end-date)))

  (getDefaultOutputName [_ io-map source-step]
    (de-jobs/get-default-output-name io-map source-step))

  (getJobStepStatus [_ job-step]
    (de-jobs/get-job-step-status job-step))

  (prepareStepSubmission [_ _ submission]
    (de-jobs/prepare-step user (update-in submission [:app_id] uuidify)))

  (getParamDefinitions [_ app-id]
    (when (util/uuid? app-id)
      (app-metadata/get-param-definitions app-id)))

  (getParamDefinitions [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/get-param-definitions app-id))

  (stopJobStep [self {:keys [job_type external_id]}]
    (when (and (apps-util/supports-job-type? self job_type)
               (not (string/blank? external_id)))
      (jex/stop-job external_id)))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (categorizeApps [_ body]
    (app-categorization/categorize-apps body))

  (permanentlyDeleteApps [this req]
    (.validateDeletionRequest this req)
    (app-metadata/permanently-delete-apps user req))

  (adminDeleteApp [_ app-id]
    (app-admin/delete-app (uuidify app-id)))

  (adminDeleteApp [_ system-id app-id]
    (validate-system-id system-id)
    (app-admin/delete-app (uuidify app-id)))

  (adminUpdateApp [_ body]
    (app-admin/update-app user (update-in body [:id] uuidify)))

  (adminUpdateApp [_ system-id body]
    (validate-system-id system-id)
    (app-admin/update-app user (update-in body [:id] uuidify)))

  (getAdminAppCategories [_ params]
    (listings/get-admin-app-groups user params))

  (adminAddCategory [_ body]
    (app-admin/add-category body))

  (adminAddCategory [_ system-id body]
    (validate-system-id system-id)
    (app-admin/add-category body))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (adminDeleteCategories [_ body]
    (app-admin/delete-categories user body))

  (adminDeleteCategory [_ category-id]
    (app-admin/delete-category user category-id))

  (adminDeleteCategory [_ system-id category-id]
    (validate-system-id system-id)
    (app-admin/delete-category user category-id))

  (adminUpdateCategory [_ body]
    (app-admin/update-category body))

  (adminUpdateCategory [_ system-id body]
    (validate-system-id system-id)
    (app-admin/update-category body))

  (getAppDocs [_ app-id]
    (when (util/uuid? app-id)
      (docs/get-app-docs user (uuidify app-id))))

  (getAppDocs [_ system-id app-id]
    (validate-system-id system-id)
    (docs/get-app-docs user (uuidify app-id)))

  (getAppIntegrationData [_ system-id app-id]
    (validate-system-id system-id)
    (integration-data/get-integration-data-for-app user (uuidify app-id)))

  (getToolIntegrationData [_ tool-id]
    (when (util/uuid? tool-id)
      (integration-data/get-integration-data-for-tool user tool-id)))

  (getToolIntegrationData [_ system-id tool-id]
    (validate-system-id system-id)
    (integration-data/get-integration-data-for-tool user tool-id))

  (updateAppIntegrationData [_ app-id integration-data-id]
    (when (util/uuid? app-id)
      (integration-data/update-integration-data-for-app user (uuidify app-id) integration-data-id)))

  (updateAppIntegrationData [_ system-id app-id integration-data-id]
    (validate-system-id system-id)
    (integration-data/update-integration-data-for-app user (uuidify app-id) integration-data-id))

  (updateToolIntegrationData [_ tool-id integration-data-id]
    (when (util/uuid? tool-id)
      (integration-data/update-integration-data-for-tool user tool-id integration-data-id)))

  (updateToolIntegrationData [_ system-id tool-id integration-data-id]
    (validate-system-id system-id)
    (integration-data/update-integration-data-for-tool user tool-id integration-data-id))

  (ownerEditAppDocs [_ app-id body]
    (when (util/uuid? app-id)
      (docs/owner-edit-app-docs user (uuidify app-id) body)))

  (ownerEditAppDocs [_ system-id app-id body]
    (validate-system-id system-id)
    (docs/owner-edit-app-docs user (uuidify app-id) body))

  (ownerAddAppDocs [_ app-id body]
    (when (util/uuid? app-id)
      (docs/owner-add-app-docs user (uuidify app-id) body)))

  (ownerAddAppDocs [_ system-id app-id body]
    (validate-system-id system-id)
    (docs/owner-add-app-docs user (uuidify app-id) body))

  (adminEditAppDocs [_ app-id body]
    (when (util/uuid? app-id)
      (docs/edit-app-docs user (uuidify app-id) body)))

  (adminEditAppDocs [_ system-id app-id body]
    (validate-system-id system-id)
    (docs/edit-app-docs user (uuidify app-id) body))

  (adminAddAppDocs [_ app-id body]
    (when (util/uuid? app-id)
      (docs/add-app-docs user (uuidify app-id) body)))

  (adminAddAppDocs [_ system-id app-id body]
    (validate-system-id system-id)
    (docs/add-app-docs user (uuidify app-id) body))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (listAppPermissions [_ app-ids]
    (when-let [uuids (util/extract-uuids app-ids)]
      (perms/list-app-permissions user uuids)))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (shareApps [self sharing-requests]
    (app-permissions/process-app-sharing-requests self sharing-requests))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (shareAppsWithUser [self app-names sharee user-app-sharing-requests]
    (app-permissions/process-user-app-sharing-requests self app-names sharee user-app-sharing-requests))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (shareAppWithUser [_ app-names sharee app-id level]
    (when (util/uuid? app-id)
      (sharing/share-app-with-user
       user sharee (uuidify app-id) level
       (partial app-permissions/app-sharing-success app-names app-id level)
       (partial app-permissions/app-sharing-failure app-names app-id level))))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (unshareApps [self unsharing-requests]
    (app-permissions/process-app-unsharing-requests self unsharing-requests))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (unshareAppsWithUser [self app-names sharee app-ids]
    (app-permissions/process-user-app-unsharing-requests self app-names sharee app-ids))

  ;; TODO: this will have to be changed when system IDs are added to the corresponding endoint.
  (unshareAppWithUser [self app-names sharee app-id]
    (when (util/uuid? app-id)
      (sharing/unshare-app-with-user
       user sharee (uuidify app-id)
       (partial app-permissions/app-unsharing-success app-names app-id)
       (partial app-permissions/app-unsharing-failure app-names app-id))))

  (hasAppPermission [_ username app-id required-level]
    (when (util/uuid? app-id)
      (perms/has-app-permission username (uuidify app-id) required-level)))

  (hasAppPermission [_ username system-id app-id required-level]
    (validate-system-id system-id)
    (perms/has-app-permission username (uuidify app-id) required-level))

  (supportsJobSharing [_ _]
    true))
