(ns apps.service.apps.de
  (:use [apps.constants :only [de-system-id]])
  (:require [clojure.string :as string]
            [apps.clients.jex :as jex]
            [apps.clients.vice :as vice]
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
            [apps.service.util :as util :refer [uuidify]]
            [apps.util.config :as cfg]))

(def ^:private supported-system-ids #{jp/de-client-name jp/interactive-client-name jp/osg-client-name})
(def ^:private validate-system-id (partial apps-util/validate-system-id supported-system-ids))
(def ^:private validate-system-ids (partial apps-util/validate-system-ids supported-system-ids))

(deftype DeApps [user]
  apps.protocols.Apps

  (getUser [_]
    user)

  (getClientName [_]
    jp/de-client-name)

  (getJobTypes [_]
    [jp/de-job-type jp/interactive-job-type jp/osg-job-type])

  (listSystemIds [_]
    (vec supported-system-ids))

  (supportsSystemId [_ system-id]
    (supported-system-ids system-id))

  (listAppCategories [_ params]
    (listings/get-app-groups user params))

  (listAppsInCategory [_ system-id category-id params]
    (validate-system-id system-id)
    (listings/list-apps-in-group user category-id params))

  (listAppsUnderHierarchy [_ root-iri attr params]
    (listings/list-apps-under-hierarchy user root-iri attr params))

  (adminListAppsUnderHierarchy [_ ontology-version root-iri attr params]
    (listings/list-apps-under-hierarchy user ontology-version root-iri attr params true))

  (listAppsInCommunity [_ community-id params]
    (listings/list-apps-in-community user community-id params false))

  (adminListAppsInCommunity [_ community-id params]
    (listings/list-apps-in-community user community-id params true))

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

  (getAppJobView [this system-id app-id]
    (.getAppJobView this system-id app-id false))

  (getAppJobView [_ system-id app-id include-hidden-params?]
    (validate-system-id system-id)
    (job-view/get-app user (uuidify app-id) include-hidden-params?))

  (deleteApp [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/delete-app user (uuidify app-id)))

  (relabelApp [_ system-id app]
    (validate-system-id system-id)
    (edit/relabel-app user (update app :id uuidify)))

  (updateApp [_ system-id app]
    (validate-system-id system-id)
    (edit/update-app user (update app :id uuidify)))

  (copyApp [_ system-id app-id]
    (validate-system-id system-id)
    (edit/copy-app user (uuidify app-id)))

  ;; FIXME: remove the admin flag when we have a better way to do this.
  (getAppDetails [_ system-id app-id admin?]
    (validate-system-id system-id)
    (listings/get-app-details user (uuidify app-id) admin?))

  (removeAppFavorite [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/remove-app-favorite user (uuidify app-id)))

  (addAppFavorite [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/add-app-favorite user (uuidify app-id)))

  (isAppPublishable [_ system-id app-id]
    (validate-system-id system-id)
    (app-validation/app-publishable? user (uuidify app-id)))

  (makeAppPublic [_ system-id app]
    (validate-system-id system-id)
    (app-metadata/make-app-public user (update app :id uuidify)))

  (deleteAppRating [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/delete-app-rating user (uuidify app-id)))

  (rateApp [_ system-id app-id rating]
    (validate-system-id system-id)
    (app-metadata/rate-app user (uuidify app-id) rating))

  (getAppTaskListing [_ system-id app-id]
    (validate-system-id system-id)
    (listings/get-app-task-listing user (uuidify app-id)))

  (getAppToolListing [_ system-id app-id]
    (validate-system-id system-id)
    (listings/get-app-tool-listing user (uuidify app-id)))

  (getAppUi [_ system-id app-id]
    (validate-system-id system-id)
    (edit/get-app-ui user (uuidify app-id)))

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

  (adminListJobsWithExternalIds [_ external-ids]
    (job-listings/admin-list-jobs-with-external-ids external-ids))

  (loadAppTables [_ qualified-app-ids]
    (validate-system-ids (set (map :system_id qualified-app-ids)))
    (->> (map :app_id qualified-app-ids)
         (ap/load-app-details)
         (map (fn [m] (assoc m :system_id de-system-id)))
         (map (juxt apps-util/to-qualified-app-id identity))
         (into {})
         (vector)))

  (submitJob [this submission]
    (validate-system-id (:system_id submission))
    (de-jobs/submit user (update-in submission [:app_id] uuidify)))

  (submitJobStep [_ job-id submission]
    (de-jobs/submit-step user job-id (update-in submission [:app_id] uuidify)))

  (translateJobStatus [self job-type status]
    (when (apps-util/supports-job-type? self job-type)
      status))

  (updateJobStatus [self job-step job status end-date]
    (when (apps-util/supports-job-type? self (:job_type job-step))
      (de-jobs/update-job-status user job-step job status end-date)))

  (getDefaultOutputName [_ io-map source-step]
    (de-jobs/get-default-output-name io-map source-step))

  (getJobStepStatus [_ job-step]
    (de-jobs/get-job-step-status job-step))

  (getJobStepHistory [_ job-step]
    (de-jobs/get-job-step-history job-step))

  (prepareStepSubmission [_ _ submission]
    (de-jobs/prepare-step user (update-in submission [:app_id] uuidify)))

  (getParamDefinitions [_ system-id app-id]
    (validate-system-id system-id)
    (app-metadata/get-param-definitions app-id))

  (stopJobStep [self {:keys [job_type external_id]}]
    (when (and (apps-util/supports-job-type? self job_type)
               (not (string/blank? external_id)))
      (if (and (cfg/vice-k8s-enabled)
               (= job_type jp/interactive-job-type))
        (vice/stop-job external_id)
        (jex/stop-job external_id))))

  (categorizeApps [_ {:keys [categories]}]
    (validate-system-ids (set (map :system_id categories)))
    (validate-system-ids (set (mapcat (fn [m] (map :system_id (:category_ids m))) categories)))
    (app-categorization/categorize-apps categories))

  (permanentlyDeleteApps [this req]
    (.validateDeletionRequest this req)
    (app-metadata/permanently-delete-apps user req))

  (adminDeleteApp [_ system-id app-id]
    (validate-system-id system-id)
    (app-admin/delete-app (uuidify app-id)))

  (adminUpdateApp [_ system-id body]
    (validate-system-id system-id)
    (app-admin/update-app user (update-in body [:id] uuidify)))

  (getAdminAppCategories [_ params]
    (listings/get-admin-app-groups user params))

  (searchAdminAppCategories [_ params]
    (listings/search-admin-app-groups params))

  (adminAddCategory [_ system-id {parent-system-id :system_id :as body}]
    (validate-system-id system-id)
    (apps-util/reject-mixed-system-ids system-id parent-system-id)
    (app-admin/add-category body))

  (adminDeleteCategory [_ system-id category-id]
    (validate-system-id system-id)
    (app-admin/delete-category user category-id))

  (adminUpdateCategory [_ system-id {parent-system-id :system_id parent-id :parent_id :as body}]
    (validate-system-id system-id)
    (when-not (and (string/blank? parent-system-id) (string/blank? parent-id))
      (apps-util/reject-mixed-system-ids system-id parent-system-id))
    (app-admin/update-category body))

  (getAppDocs [_ system-id app-id]
    (validate-system-id system-id)
    (docs/get-app-docs user (uuidify app-id)))

  (getAppIntegrationData [_ system-id app-id]
    (validate-system-id system-id)
    (integration-data/get-integration-data-for-app user (uuidify app-id)))

  (getToolIntegrationData [_ system-id tool-id]
    (validate-system-id system-id)
    (integration-data/get-integration-data-for-tool user tool-id))

  (updateAppIntegrationData [_ system-id app-id integration-data-id]
    (validate-system-id system-id)
    (integration-data/update-integration-data-for-app user (uuidify app-id) integration-data-id))

  (updateToolIntegrationData [_ system-id tool-id integration-data-id]
    (validate-system-id system-id)
    (integration-data/update-integration-data-for-tool user tool-id integration-data-id))

  (ownerEditAppDocs [_ system-id app-id body]
    (validate-system-id system-id)
    (docs/owner-edit-app-docs user (uuidify app-id) body))

  (ownerAddAppDocs [_ system-id app-id body]
    (validate-system-id system-id)
    (docs/owner-add-app-docs user (uuidify app-id) body))

  (adminEditAppDocs [_ system-id app-id body]
    (validate-system-id system-id)
    (docs/edit-app-docs user (uuidify app-id) body))

  (adminAddAppDocs [_ system-id app-id body]
    (validate-system-id system-id)
    (docs/add-app-docs user (uuidify app-id) body))

  (listAppPermissions [_ qualified-app-ids params]
    (validate-system-ids (set (map :system_id qualified-app-ids)))
    (perms/list-app-permissions user (mapv (comp uuidify :app_id) qualified-app-ids) params))

  (shareApps [self sharing-requests]
    (app-permissions/process-app-sharing-requests self sharing-requests))

  (shareAppsWithSubject [self app-names sharee user-app-sharing-requests]
    (app-permissions/process-subject-app-sharing-requests self app-names sharee user-app-sharing-requests))

  (shareAppWithSubject [_ app-names sharee system-id app-id level]
    (validate-system-id system-id)
    (sharing/share-app-with-subject
     user sharee (uuidify app-id) level
     (partial app-permissions/app-sharing-success app-names system-id app-id level)
     (partial app-permissions/app-sharing-failure app-names system-id app-id level)))

  (unshareApps [self unsharing-requests]
    (app-permissions/process-app-unsharing-requests self unsharing-requests))

  (unshareAppsWithSubject [self app-names sharee user-app-unsharing-requests]
    (app-permissions/process-subject-app-unsharing-requests self app-names sharee user-app-unsharing-requests))

  (unshareAppWithSubject [self app-names sharee system-id app-id]
    (validate-system-id system-id)
    (sharing/unshare-app-with-subject
     user sharee (uuidify app-id)
     (partial app-permissions/app-unsharing-success app-names system-id app-id)
     (partial app-permissions/app-unsharing-failure app-names system-id app-id)))

  (hasAppPermission [_ username system-id app-id required-level]
    (validate-system-id system-id)
    (perms/has-app-permission username (uuidify app-id) required-level))

  (supportsJobSharing [_ _]
    true))
