(ns apps.routes.tools
  (:use [common-swagger-api.schema]
        [common-swagger-api.schema.apps :only [AppListing ToolAppListingResponses]]
        [common-swagger-api.schema.apps.admin.apps :only [AdminAppListing ToolAdminAppListingResponses]]
        [common-swagger-api.schema.containers
         :only [DataContainer
                Device
                Image
                Volume
                VolumesFrom]]
        [common-swagger-api.schema.integration-data
         :only [IntegrationData
                IntegrationDataIdPathParam]]
        [apps.constants :only [de-system-id]]
        [apps.containers]
        [apps.routes.params]
        [apps.routes.schemas.containers]
        [apps.routes.schemas.tool]
        [apps.tools
         :only [admin-add-tools
                admin-delete-tool
                admin-list-tools
                admin-publish-tool
                admin-update-tool
                get-tool
                list-tools
                submit-tool-request
                user-get-tool]]
        [apps.tools.private
         :only [add-private-tool
                delete-private-tool
                update-private-tool]]
        [apps.user :only [current-user]]
        [apps.util.coercions :only [coerce!]]
        [apps.util.service]
        [slingshot.slingshot :only [throw+]]
        [ring.util.http-response :only [ok]])
  (:require [apps.metadata.tool-requests :as tool-requests]
            [apps.routes.schemas.permission :as permission]
            [apps.service.apps :as apps]
            [apps.service.apps.de.listings :as app-listings]
            [apps.tools.permissions :as tool-permissions]
            [apps.tools.sharing :as tool-sharing]
            [common-swagger-api.routes]                     ;; for :description-file
            [common-swagger-api.schema.apps.permission :as perm-schema]
            [common-swagger-api.schema.containers :as containers-schema]
            [common-swagger-api.schema.tools :as schema]
            [common-swagger-api.schema.tools.admin :as admin-schema]
            [compojure.api.middleware :as middleware]))

(def entrypoint-warning
  (str "

#### Warning: Use caution with Entrypoint settings!
    Do not add a tool without an `entrypoint` setting if its Docker image also does not have a
    default `ENTRYPOINT`. If a tool like this is required, then its `network_mode` setting should be
    set to `none` to contain any risky scripts run by this tool."))

(def volumes-warning
  (str "

#### Warning: Use caution with Volumes settings!
    Do not add `volumes` or `volumes_from` settings to tools unless it is certain that tool"
  " is authorized to access that data."))

(defn requester
  "Handles wrapping request maps or throwing a not-found error if no containers are found for a tool."
  [tool-id retval]
  (if (nil? retval)
         (throw+ {:type  :clojure-commons.exception/not-found
                  :error (str "A container for " tool-id " was not found.")})
         (ok retval)))

(defroutes container-images
  (GET "/" []
        :query [params SecuredQueryParams]
        :return Images
        :summary "List Container Images"
        :description "Returns all of the container images defined in the database."
        (ok (list-images)))

  (GET "/:image-id" []
        :path-params [image-id :- ImageId]
        :query [params SecuredQueryParams]
        :return Image
        :summary "Container Image"
        :description "Returns a JSON description of a container image."
        (ok (image-info image-id)))

  (POST "/" []
        :query [params SecuredQueryParams]
        :body [body containers-schema/NewImage]
        :return Image
        :summary "Add Container Image"
        :description "Adds a new container image to the system."
        (ok (find-or-add-image-info body)))

  (DELETE "/:image-id" []
           :path-params [image-id :- ImageId]
           :query [{:keys [user]} SecuredQueryParams]
           :summary "Delete Container Image"
           :description "Deletes a container image from the system."
           (ok (delete-image image-id user)))

  (PATCH "/:image-id" []
          :path-params [image-id :- ImageId]
          :query [{:keys [user overwrite-public]} ImageUpdateParams]
          :body [body ImageUpdateRequest]
          :return Image
          :summary "Update Container Image Info"
          :description
"Updates a container's image settings.

#### Danger Zone

    Do not update image settings that are in use by tools in public apps unless it is certain the
    new image settings will not break reproducibility for those apps.
    If required, the `overwrite-public` flag may be used to update image settings in use by
    public apps."
          (ok (modify-image-info image-id user overwrite-public body)))

  (GET "/:image-id/public-tools" []
        :path-params [image-id :- ImageId]
        :query [params SecuredQueryParams]
        :return ImagePublicAppToolListing
        :summary "Container Image Public Tools"
        :description "Returns a list of a public tools using the given image ID."
        (ok (image-public-tools image-id))))

(defroutes admin-data-containers
  (PATCH "/:data-container-id" []
          :path-params [data-container-id :- DataContainerIdParam]
          :query [params SecuredQueryParams]
          :body [body DataContainerUpdateRequest]
          :return DataContainer
          :summary "Update Data Container"
          :description "Updates a data container's settings."
          (ok (modify-data-container data-container-id body))))

(defroutes tools
  (GET "/" []
       :query [params ToolSearchParams]
       :return schema/ToolListing
       :summary schema/ToolListingSummary
       :description schema/ToolListingDocs
       (ok (list-tools params)))

  (POST "/" []
        :query [params SecuredQueryParamsRequired]
        :middleware [schema/coerce-tool-import-requests]
        :body [body schema/PrivateToolImportRequest]
        :responses schema/PrivateToolImportResponses
        :summary schema/ToolAddSummary
        :description-file "docs/tools/tool-add.md"
        (ok (add-private-tool current-user body)))

  (POST "/permission-lister" []
        :query [params permission/PermissionListerQueryParams]
        :body [{:keys [tools]} perm-schema/ToolIdList]
        :responses perm-schema/ToolPermissionsListingResponses
        :summary schema/ToolPermissionsListingSummary
        :description schema/ToolPermissionsListingDocs
        (ok (tool-permissions/list-tool-permissions current-user tools params)))

  (POST "/sharing" []
        :query [params SecuredQueryParams]
        :body [{:keys [sharing]} perm-schema/ToolSharingRequest]
        :return perm-schema/ToolSharingResponse
        :summary perm-schema/ToolSharingSummary
        :description perm-schema/ToolSharingDocs
        (ok (tool-sharing/share-tools current-user sharing)))

  (POST "/unsharing" []
        :query [params SecuredQueryParams]
        :body [{:keys [unsharing]} perm-schema/ToolUnsharingRequest]
        :return perm-schema/ToolUnsharingResponse
        :summary perm-schema/ToolUnsharingSummary
        :description perm-schema/ToolUnsharingDocs
        (ok (tool-sharing/unshare-tools current-user unsharing)))

  (DELETE "/:tool-id" []
          :path-params [tool-id :- schema/ToolIdParam]
          :query [{:keys [user force-delete]} PrivateToolDeleteParams]
          :coercion middleware/no-response-coercion
          :responses schema/ToolDeleteResponses
          :summary schema/ToolDeleteSummary
          :description schema/ToolDeleteDocs
          (ok (delete-private-tool user tool-id force-delete)))

  (GET "/:tool-id" []
       :path-params [tool-id :- schema/ToolIdParam]
       :query [{:keys [user]} SecuredQueryParams]
       :responses schema/ToolDetailsResponses
       :summary schema/ToolDetailsSummary
       :description schema/ToolDetailsDocs
       (ok (user-get-tool user tool-id)))

  (PATCH "/:tool-id" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [{:keys [user]} SecuredQueryParams]
         :middleware [schema/coerce-tool-import-requests]
         :body [body schema/PrivateToolUpdateRequest]
         :responses schema/ToolUpdateResponses
         :summary schema/ToolUpdateSummary
         :description-file "docs/tools/tool-update.md"
         (ok (update-private-tool user (assoc body :id tool-id))))

  (GET "/:tool-id/apps" []
       :path-params [tool-id :- schema/ToolIdParam]
       :query [params SecuredQueryParams]
       :responses ToolAppListingResponses
       :summary schema/ToolAppListingSummary
       :description schema/ToolAppListingDocs
       (ok (coerce! AppListing
                    (app-listings/user-list-apps-by-tool current-user tool-id params))))

  (GET "/:tool-id/integration-data" []
        :path-params [tool-id :- schema/ToolIdParam]
        :query [params SecuredQueryParams]
        :return IntegrationData
        :summary schema/ToolIntegrationDataListingSummary
        :description schema/ToolIntegrationDataListingDocs
        (ok (apps/get-tool-integration-data current-user de-system-id tool-id))))

(defroutes tool-requests
  (GET "/" []
       :query [params ToolRequestListingParams]
       :return schema/ToolRequestListing
       :summary schema/ToolInstallRequestListingSummary
       :description schema/ToolInstallRequestListingDocs
       (ok (tool-requests/list-tool-requests (assoc params :username (:username current-user)))))

  (POST "/" []
        :query [params SecuredQueryParams]
        :body [body schema/ToolRequest]
        :return schema/ToolRequestDetails
        :summary schema/ToolInstallRequestSummary
        :description schema/ToolInstallRequestDocs
        (ok (submit-tool-request current-user body)))

  (GET "/status-codes" []
       :query [params ToolRequestStatusCodeListingParams]
       :return schema/ToolRequestStatusCodeListing
       :summary schema/ToolInstallRequestStatusCodeListingSummary
       :description schema/ToolInstallRequestStatusCodeListingDocs
       (ok (tool-requests/list-tool-request-status-codes params))))

(defroutes admin-tools
  (GET "/" []
       :query [params ToolSearchParams]
       :return schema/ToolListing
       :summary schema/ToolListingSummary
       :description admin-schema/ToolListingDocs
       (ok (admin-list-tools params)))

  (POST "/" []
        :query [params SecuredQueryParams]
        :middleware [schema/coerce-tool-list-import-request]
        :body [body admin-schema/ToolsImportRequest]
        :responses admin-schema/ToolsImportResponses
        :summary admin-schema/ToolsImportSummary
        :description-file "docs/tools/admin/tools-import.md"
        (ok (admin-add-tools body)))

  (DELETE "/:tool-id" []
          :path-params [tool-id :- schema/ToolIdParam]
          :query [{:keys [user]} SecuredQueryParams]
          :coercion middleware/no-response-coercion
          :responses admin-schema/ToolDeleteResponses
          :summary admin-schema/ToolDeleteSummary
          :description admin-schema/ToolDeleteDocs
          (ok (admin-delete-tool user tool-id)))

  (GET "/:tool-id" []
       :path-params [tool-id :- schema/ToolIdParam]
       :query [{:keys [user]} SecuredQueryParams]
       :responses admin-schema/ToolDetailsResponses
       :summary schema/ToolDetailsSummary
       :description admin-schema/ToolDetailsDocs
       (ok (get-tool user tool-id)))

  (PATCH "/:tool-id" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [{:keys [user overwrite-public]} ToolUpdateParams]
         :middleware [schema/coerce-tool-import-requests]
         :body [body admin-schema/ToolUpdateRequest]
         :responses admin-schema/ToolUpdateResponses
         :summary admin-schema/ToolUpdateSummary
         :description-file "docs/tools/admin/tool-update.md"
         (ok (admin-update-tool user overwrite-public (assoc body :id tool-id))))

  (GET "/:tool-id/apps" []
       :path-params [tool-id :- schema/ToolIdParam]
       :query [params SecuredQueryParams]
       :responses ToolAdminAppListingResponses
       :summary schema/ToolAppListingSummary
       :description schema/ToolAppListingDocs
       (ok (coerce! AdminAppListing
                    (app-listings/list-apps-by-tool current-user tool-id params true))))

  (POST "/:tool-id/container/devices" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body containers-schema/NewDevice]
         :return Device
         :summary "Adds Device To Tool Container"
         :description "Adds a new device to a tool container."
         (requester tool-id (add-tool-device tool-id body)))

  (DELETE "/:tool-id/container/devices/:device-id" []
           :path-params [tool-id :- schema/ToolIdParam device-id :- DeviceIdParam]
           :query [params SecuredQueryParams]
           :return nil
           :summary "Delete a container device"
           :description "Deletes a device from the tool's container"
           (ok (delete-tool-device tool-id device-id)))

  (POST "/:tool-id/container/devices/:device-id/host-path" []
         :path-params [tool-id :- schema/ToolIdParam device-id :- DeviceIdParam]
         :query [params SecuredQueryParams]
         :body [body DeviceHostPath]
         :return DeviceHostPath
         :summary "Update Tool Container Device Host Path"
         :description "This endpoint updates a device's host path for the tool's container."
         (requester tool-id (update-device-field tool-id device-id :host_path (:host_path body))))

  (POST "/:tool-id/container/devices/:device-id/container-path" []
         :path-params [tool-id :- schema/ToolIdParam device-id :- DeviceIdParam]
         :query [params SecuredQueryParams]
         :body [body DeviceContainerPath]
         :return DeviceContainerPath
         :summary "Update Tool Device Container Path"
         :description "This endpoint updates a device's container path for the tool's container."
         (requester tool-id (update-device-field tool-id device-id :container_path (:container_path body))))

  (POST "/:tool-id/container/entrypoint" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body Entrypoint]
         :return Entrypoint
         :summary "Update Tool Container Entrypoint"
         :description (str
                        "This endpoint updates an entrypoint for the tool's container."
                        entrypoint-warning)
         (requester tool-id (update-settings-field tool-id :entrypoint (:entrypoint body))))

  (POST "/:tool-id/container/cpu-shares" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body CPUShares]
         :return CPUShares
         :summary "Update Tool Container CPU Shares"
         :description "This endpoint updates a the CPU shares for the tool's container."
         (requester tool-id (update-settings-field tool-id :cpu_shares (:cpu_shares body))))

  (POST "/:tool-id/container/memory-limit" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body MemoryLimit]
         :return MemoryLimit
         :summary "Update Tool Container Memory Limit"
         :description "This endpoint updates a the memory limit for the tool's container."
         (requester tool-id (update-settings-field tool-id :memory_limit (:memory_limit body))))

  (POST "/:tool-id/container/min-memory-limit" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body MinMemoryLimit]
         :return MinMemoryLimit
         :summary "Update Tool Container Minimum Memory Limit"
         :description "This endpoint updates the minimum memory limit for the tool's container."
         (requester tool-id (update-settings-field tool-id :min_memory_limit (:min_memory_limit body))))

  (POST "/:tool-id/container/min-cpu-cores" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body MinCPUCores]
         :return MinCPUCores
         :summary "Update Tool Container Minimum CPU Cores"
         :description "This endpoint updates the minimum number of CPU cores for the tool's container."
         (requester tool-id (update-settings-field tool-id :min_cpu_cores (:min_cpu_cores body))))

  (POST "/:tool-id/container/min-disk-space" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body MinDiskSpace]
         :return MinDiskSpace
         :summary "Update Tool Container Minimum Disk Space Requirement"
         :description "This endpoint updates the minimum amount of disk space required for the tool's container."
         (requester tool-id (update-settings-field tool-id :min_disk_space (:min_disk_space body))))

  (POST "/:tool-id/container/network-mode" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body NetworkMode]
         :return NetworkMode
         :summary "Update Tool Container Network Mode"
         :description "This endpoint updates a the network mode for the tool's container."
         (requester tool-id (update-settings-field tool-id :network_mode (:network_mode body))))

  (POST "/:tool-id/container/working-directory" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body WorkingDirectory]
         :return WorkingDirectory
         :summary "Update Tool Container Working Directory"
         :description "This endpoint updates the working directory for the tool's container."
         (requester tool-id (update-settings-field tool-id :working_directory (:working_directory body))))

  (POST "/:tool-id/container/name" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body ContainerName]
         :return ContainerName
         :summary "Update Tool Container Name"
         :description "This endpoint updates the container name for the tool's container."
         (requester tool-id (update-settings-field tool-id :name (:name body))))

  (POST "/:tool-id/container/volumes" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body containers-schema/NewVolume]
         :return Volume
         :summary "Tool Container Volume Information"
         :description (str
                        "This endpoint updates volume information for the container associated with a tool."
                        volumes-warning)
         (requester tool-id (add-tool-volume tool-id body)))

  (DELETE "/:tool-id/container/volumes/:volume-id" []
           :path-params [tool-id :- schema/ToolIdParam volume-id :- VolumeIdParam]
           :query [params SecuredQueryParams]
           :return nil
           :summary "Delete Tool Container Volume"
           :description "Deletes a volume from a tool container."
           (ok (delete-tool-volume tool-id volume-id)))

  (POST "/:tool-id/container/volumes/:volume-id/host-path" []
         :path-params [tool-id :- schema/ToolIdParam volume-id :- VolumeIdParam]
         :query [params SecuredQueryParams]
         :body [body VolumeHostPath]
         :return VolumeHostPath
         :summary "Update Tool Container Volume Host Path"
         :description (str
                        "This endpoint updates a volume host path for the tool's container."
                        volumes-warning)
         (requester tool-id (update-volume-field tool-id volume-id :host_path (:host_path body))))

  (POST "/:tool-id/container/volumes/:volume-id/container-path" []
         :path-params [tool-id :- schema/ToolIdParam volume-id :- VolumeIdParam]
         :query [params SecuredQueryParams]
         :body [body VolumeContainerPath]
         :return VolumeContainerPath
         :summary "Update Tool Container Volume Container Path"
         :description (str
                        "This endpoint updates a volume container path for the tool's container."
                        volumes-warning)
         (requester tool-id (update-volume-field tool-id volume-id :container_path (:container_path body))))

  (PUT "/:tool-id/container/volumes-from" []
         :path-params [tool-id :- schema/ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body containers-schema/NewVolumesFrom]
         :return VolumesFrom
         :summary "Adds A Volume Host Container"
         :description (str
                        "Adds a new container from which the tool container will bind mount volumes."
                        volumes-warning)
         (requester tool-id (add-tool-volumes-from tool-id body)))

  (DELETE "/:tool-id/container/volumes-from/:volumes-from-id" []
           :path-params [tool-id :- schema/ToolIdParam volumes-from-id :- VolumesFromIdParam]
           :query [params SecuredQueryParams]
           :return nil
           :summary "Delete Tool Container Volumes From Information"
           :description "Deletes a container name that the tool container should import volumes from."
           (ok (delete-tool-volumes-from tool-id volumes-from-id)))

  (PUT "/:tool-id/integration-data/:integration-data-id" []
        :path-params [tool-id :- schema/ToolIdParam integration-data-id :- IntegrationDataIdPathParam]
        :query [params SecuredQueryParams]
        :return IntegrationData
        :summary admin-schema/ToolIntegrationUpdateSummary
        :description admin-schema/ToolIntegrationUpdateDocs
        (ok (apps/update-tool-integration-data current-user de-system-id tool-id integration-data-id)))

  (POST "/:tool-id/publish" []
        :path-params [tool-id :- schema/ToolIdParam]
        :query [params SecuredQueryParams]
        :middleware [schema/coerce-tool-import-requests]
        :body [body admin-schema/ToolUpdateRequest]
        :responses admin-schema/ToolPublishResponses
        :summary admin-schema/ToolPublishSummary
        :description admin-schema/ToolPublishDocs
        (ok (admin-publish-tool current-user (assoc body :id tool-id)))))
