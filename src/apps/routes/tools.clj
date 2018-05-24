(ns apps.routes.tools
  (:use [common-swagger-api.schema]
        [apps.constants :only [de-system-id]]
        [apps.containers]
        [apps.routes.params]
        [apps.routes.schemas.containers]
        [apps.routes.schemas.integration-data :only [IntegrationData]]
        [apps.routes.schemas.app :only [AdminAppListing AppListing]]
        [apps.routes.schemas.tool]
        [apps.tools :only [admin-add-tools
                           admin-delete-tool
                           admin-list-tools
                           admin-publish-tool
                           admin-update-tool
                           get-tool
                           list-tools
                           submit-tool-request
                           user-get-tool]]
        [apps.tools.private :only [add-private-tool delete-private-tool update-private-tool]]
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
        :body [body NewImage]
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

(defroutes data-containers
  (GET "/" []
        :query [params SecuredQueryParams]
        :return DataContainers
        :summary "List Data Containers"
        :description "Lists all of the available data containers."
        (ok (list-data-containers)))

  (GET "/:data-container-id" []
        :path-params [data-container-id :- DataContainerIdParam]
        :query [params SecuredQueryParams]
        :return DataContainer
        :summary "Data Container"
        :description "Returns a JSON description of a data container."
        (ok (data-container data-container-id))))

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
       :return ToolListing
       :summary "List Tools"
       :description "This endpoint allows users to get a listing of all Tools accessible to the user."
       (ok (list-tools params)))

  (POST "/" []
        :query [params SecuredQueryParamsRequired]
        :body [body (describe PrivateToolImportRequest "The private Tool to import.")]
        :responses (merge CommonResponses
                          {200 {:schema      ToolDetails
                                :description "The new Tool details."}
                           400 PrivateToolImportResponse400})
        :summary "Add Private Tool"
        :description
   "This service adds a new private Tool to the DE for the requesting user.

Note that `type` is always set to `executable`, `restricted` is always set to `true`,
and `container.network_mode` is always set to `none`, even if another value is set in the request.

Configured default values will be used for the `time_limit_seconds`, `container.pids_limit`, and `container.memory_limit` fields
The request may include a value less than the configured default if it's also greater than 0,
otherwise the default value will be used."
        (ok (add-private-tool current-user body)))

  (POST "/permission-lister" []
        :query [params permission/PermissionListerQueryParams]
        :body [{:keys [tools]} (describe permission/ToolIdList "The Tool permission listing request.")]
        :responses (merge CommonResponses
                          {200 {:schema      permission/ToolPermissionListing
                                :description "The Tool permission listings."}
                           403 {:schema      ErrorResponseForbidden
                                :description "The requesting user does not have `read` permission for some Tool(s) in the request."}
                           404 {:schema      ErrorResponseNotFound
                                :description "Some `tool-id`(s) in the request do not exist."}})
        :summary "List Tool Permissions"
        :description "This endpoint allows the caller to list the permissions for one or more Tools.
        The authenticated user must have read permission on every Tool in the request body for this endpoint to succeed."
        (ok (tool-permissions/list-tool-permissions current-user tools params)))

  (POST "/sharing" []
        :query [params SecuredQueryParams]
        :body [{:keys [sharing]} (describe permission/ToolSharingRequest "The Tool sharing request.")]
        :return permission/ToolSharingResponse
        :summary "Add Tool Permissions"
        :description "This endpoint allows the caller to share multiple Tools with multiple users.
        The authenticated user must have ownership permission to every Tool in the request body for this endpoint to fully succeed.
        Note: this is a potentially slow operation and the response is returned synchronously.
        The DE UI handles this by allowing the user to continue working while the request is being processed.
        When calling this endpoint, please be sure that the response timeout is long enough.
        Using a response timeout that is too short will result in an exception on the client side.
        On the server side, the result of the sharing operation when a connection is lost is undefined.
        It may be worthwhile to repeat failed or timed out calls to this endpoint."
        (ok (tool-sharing/share-tools current-user sharing)))

  (POST "/unsharing" []
        :query [params SecuredQueryParams]
        :body [{:keys [unsharing]} (describe permission/ToolUnsharingRequest "The Tool unsharing request.")]
        :return permission/ToolUnsharingResponse
        :summary "Revoke Tool Permissions"
        :description "This endpoint allows the caller to revoke permission to access one or more Tools from one or more users.
        The authenticated user must have ownership permission to every Tool in the request body for this endoint to fully succeed.
        Note: like Tool sharing, this is a potentially slow operation."
        (ok (tool-sharing/unshare-tools current-user unsharing)))

  (DELETE "/:tool-id" []
          :path-params [tool-id :- ToolIdParam]
          :query [{:keys [user force-delete]} PrivateToolDeleteParams]
          :coercion middleware/no-response-coercion
          :responses (merge CommonResponses
                            {200 {:description "The Tool was successfully deleted."}
                             400 {:schema      ErrorResponseNotWritable
                                  :description "The Tool could not be deleted."}
                             403 {:schema      ErrorResponseForbidden
                                  :description "The requesting user does not have permission to delete this Tool."}
                             404 {:schema      ErrorResponseNotFound
                                  :description "A Tool with the given `tool-id` does not exist."}})
          :summary "Delete a Private Tool"
          :description "Deletes a private Tool, as long as it is not in use by any Apps.
          The requesting user must have ownership permission for the Tool.
          If the Tool is already in use in private Apps,
          then an `ERR_NOT_WRITEABLE` will be returned along with a listing of the Apps using this Tool,
          unless the `force-delete` flag is set to `true`."
          (ok (delete-private-tool user tool-id force-delete)))

  (GET "/:tool-id" []
       :path-params [tool-id :- ToolIdParam]
       :query [{:keys [user]} SecuredQueryParams]
       :responses (merge CommonResponses
                         {200 {:schema      ToolDetails
                               :description "The Tool details."}
                          403 {:schema      ErrorResponseForbidden
                               :description "The requesting user does not have `read` permission for the Tool."}
                          404 {:schema      ErrorResponseNotFound
                               :description "The `tool-id` does not exist."}})
       :summary "Get a Tool"
       :description "This endpoint returns the details for one tool accessible to the user."
       (ok (user-get-tool user tool-id)))

  (PATCH "/:tool-id" []
         :path-params [tool-id :- ToolIdParam]
         :query [{:keys [user]} SecuredQueryParams]
         :body [body (describe PrivateToolUpdateRequest "The private Tool to update.")]
         :responses (merge CommonResponses
                           {200 {:schema      ToolDetails
                                 :description "The updated Tool details."}
                            400 PrivateToolImportResponse400})
         :summary "Update a Private Tool"
         :description "This service updates a private Tool definition in the DE.
As with new private Tools, `type` is always set to `executable` and `restricted` is always set to `true`,
even if other values are set in the request,
and a configured limit may override the `time_limit_seconds` field set in the request.

**Note**: If the `container` object is omitted in the request, then existing container settings will not be modified,
but if the `container` object is present in the request, then all container settings must be included in it.
Any existing settings not included in the request's `container` object will be removed,
except `network_mode` is always set to `none` and configured limits may override values set (or omitted)
for the `pids_limit` and `memory_limit` fields."
         (ok (update-private-tool user (assoc body :id tool-id))))

  (GET "/:tool-id/apps" []
       :path-params [tool-id :- ToolIdParam]
       :query [params SecuredQueryParams]
       :responses (merge CommonResponses
                         {200 {:schema      AppListing
                               :description "The listing of Apps using the given Tool."}
                          404 {:schema      ErrorResponseNotFound
                               :description "The `tool-id` does not exist."}})
       :summary "Get Apps by Tool"
       :description "This endpoint returns a listing of Apps using the given Tool."
       (ok (coerce! AppListing
                    (app-listings/user-list-apps-by-tool current-user tool-id params))))

  (GET "/:tool-id/container" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return ToolContainer
        :summary "Tool Container Information"
        :description "This endpoint returns container information associated with a tool. This endpoint
        returns a 404 if the tool is not run inside a container."
        (requester tool-id (tool-container-info tool-id)))

  (GET "/:tool-id/container/devices" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return Devices
        :summary "Tool Container Device Information"
        :description "Returns device information for the container associated with a tool."
        (requester tool-id (tool-device-info tool-id)))

  (GET "/:tool-id/container/devices/:device-id" []
        :path-params [tool-id :- ToolIdParam,
                      device-id :- DeviceIdParam]
        :query [params SecuredQueryParams]
        :return Device
        :summary "Tool Container Device Information"
        :description "Returns device information for the container associated with a tool."
        (requester tool-id (tool-device tool-id device-id)))

  (GET "/:tool-id/container/devices/:device-id/host-path" []
        :path-params [tool-id :- ToolIdParam device-id :- DeviceIdParam]
        :query [params SecuredQueryParams]
        :return DeviceHostPath
        :summary "Tool Container Device Host Path"
        :description "Returns a device's host path."
        (requester tool-id (device-field tool-id device-id :host_path)))

  (GET "/:tool-id/container/devices/:device-id/container-path" []
        :path-params [tool-id :- ToolIdParam device-id :- DeviceIdParam]
        :query [params SecuredQueryParams]
        :return DeviceContainerPath
        :summary "Tool Device Container Path"
        :description "Returns a device's in-container path."
        (requester tool-id (device-field tool-id device-id :container_path)))

  (GET "/:tool-id/container/cpu-shares" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return CPUShares
        :summary "Tool Container CPU Shares"
        :description "Returns the number of shares of the CPU that the tool container will receive."
        (requester tool-id (get-settings-field tool-id :cpu_shares)))

  (GET "/:tool-id/container/memory-limit" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return MemoryLimit
        :summary "Tool Container Memory Limit"
        :description "Returns the maximum amount of RAM that can be allocated to the tool container (in bytes)."
        (requester tool-id (get-settings-field tool-id :memory_limit)))

  (GET "/:tool-id/container/min-memory-limit" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return MinMemoryLimit
        :summary "Tool Container Minimum Memory Requirement"
        :description "Returns the minimum amount of RAM that is required to run the tool container (in bytes)."
        (requester tool-id (get-settings-field tool-id :min_memory_limit)))

  (GET "/:tool-id/container/min-cpu-cores" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return MinCPUCores
        :summary "Tool Container Minimum CPU Cores Requirement"
        :description "Returns the minimum number of CPU cores that is required to run the tool container."
        (requester tool-id (get-settings-field tool-id :min_cpu_cores)))

  (GET "/:tool-id/container/min-disk-space" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return MinDiskSpace
        :summary "Tool Container Minimum Disk Space"
        :description "Returns the minimum disk space requirement for the tool container."
        (requester tool-id (get-settings-field tool-id :min_disk_space)))

  (GET "/:tool-id/container/network-mode" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return NetworkMode
        :summary "Tool Container Network Mode"
        :description "Returns the network mode the tool container will operate in. Usually 'bridge' or 'none'."
        (requester tool-id (get-settings-field tool-id :network_mode)))

  (GET "/:tool-id/container/working-directory" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return WorkingDirectory
        :summary "Tool Container Working Directory"
        :description "Sets the initial working directory for the tool container."
        (requester tool-id (get-settings-field tool-id :working_directory)))

  (GET "/:tool-id/container/entrypoint" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return Entrypoint
        :summary "Tool Container Entrypoint"
        :description "Get the entrypoint setting for the tool container."
        (requester tool-id (get-settings-field tool-id :entrypoint)))

  (GET "/:tool-id/container/name" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return ContainerName
        :summary "Tool Container Name"
        :description "The user supplied name that the container will be assigned when it runs."
        (requester tool-id (get-settings-field tool-id :name)))

  (GET "/:tool-id/container/volumes" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return Volumes
        :summary "Tool Container Volume Information"
        :description "Returns volume information for the container associated with a tool."
        (requester tool-id (tool-volume-info tool-id)))

  (GET "/:tool-id/container/volumes/:volume-id" []
        :path-params [tool-id :- ToolIdParam volume-id :- VolumeIdParam]
        :query [params SecuredQueryParams]
        :return Volume
        :summary "Tool Container Volume Information"
        :description "Returns volume information for the container associated with a tool."
        (requester tool-id (tool-volume tool-id volume-id)))

  (GET "/:tool-id/container/volumes/:volume-id/host-path" []
        :path-params [tool-id :- ToolIdParam volume-id :- VolumeIdParam]
        :query [params SecuredQueryParams]
        :return VolumeHostPath
        :summary "Tool Container Volume Host Path"
        :description "Returns volume host path for the container associated with a tool."
        (requester tool-id (volume-field tool-id volume-id :host_path)))

  (GET "/:tool-id/container/volumes/:volume-id/container-path" []
        :path-params [tool-id :- ToolIdParam volume-id :- VolumeIdParam]
        :query [params SecuredQueryParams]
        :return VolumeContainerPath
        :summary "Tool Container Volume Container Path"
        :description "Returns volume container path for the container associated with a tool."
        (requester tool-id (volume-field tool-id volume-id :container_path)))

  (GET "/:tool-id/container/volumes-from" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return VolumesFromList
        :summary "Tool Container Volumes From Information"
        :description "Returns a list of container names that the container associated with the tool should import volumes from."
        (requester tool-id (tool-volumes-from-info tool-id)))

  (GET "/:tool-id/container/volumes-from/:volumes-from-id" []
        :path-params [tool-id :- ToolIdParam volumes-from-id :- VolumesFromIdParam]
        :query [params SecuredQueryParams]
        :return VolumesFrom
        :summary "Tool Container Volumes From Information"
        :description "Returns the data container settings for the given `volumes-from-id` the tool
         should import volumes from."
        (requester tool-id (tool-volumes-from tool-id volumes-from-id)))

  (GET "/:tool-id/integration-data" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :return IntegrationData
        :summary "Return the Integration Data Record for a Tool"
        :description "This service returns the integration data associated with an app."
        (ok (apps/get-tool-integration-data current-user de-system-id tool-id))))

(defroutes tool-requests
  (GET "/" []
        :query [params ToolRequestListingParams]
        :return ToolRequestListing
        :summary "List Tool Requests"
        :description "This endpoint lists high level details about tool requests that have been submitted.
        A user may track their own tool requests with this endpoint."
        (ok (tool-requests/list-tool-requests (assoc params :username (:username current-user)))))

  (POST "/" []
         :query [params SecuredQueryParams]
         :body [body (describe ToolRequest
                       "A tool installation request. One of `source_url` or `source_upload_file`
                        fields are required, but not both.")]
         :return ToolRequestDetails
         :summary "Request Tool Installation"
         :description "This service submits a request for a tool to be installed so that it can be used
         from within the Discovery Environment. The installation request and all status updates
         related to the tool request will be tracked in the Discovery Environment database."
         (ok (submit-tool-request current-user body)))

  (GET "/status-codes" []
        :query [params StatusCodeListingParams]
        :summary "List Tool Request Status Codes"
        :return StatusCodeListing
        :description "Tool request status codes are largely arbitrary, but once they've been used once,
        they're stored in the database so that they can be reused easily. This endpoint allows the
        caller to list the known status codes."
        (ok (tool-requests/list-tool-request-status-codes params))))

(defroutes admin-tools
  (GET "/" []
       :query [params ToolSearchParams]
       :return ToolListing
       :summary "List Tools"
       :description "This endpoint allows admins to get a listing of all Tools."
       (ok (admin-list-tools params)))

  (POST "/" []
        :query [params SecuredQueryParams]
        :body [body (describe ToolsImportRequest "The Tools to import.")]
        :responses (merge CommonResponses
                          {200 {:schema      ToolIdsList
                                :description "A list of the new Tool IDs."}
                           400 {:schema      ErrorResponseExists
                                :description "A Tool with the given `name` already exists."}})
        :summary "Add new Tools."
        :description (str "This service adds new Tools to the DE."
                          entrypoint-warning
                          volumes-warning)
        (ok (admin-add-tools body)))

  (DELETE "/:tool-id" []
          :path-params [tool-id :- ToolIdParam]
          :query [{:keys [user]} SecuredQueryParams]
          :coercion middleware/no-response-coercion
          :responses (merge CommonResponses
                            {200 {:description "The Tool was successfully deleted."}
                             400 {:schema      ErrorResponseNotWritable
                                  :description "The Tool is already in use by apps and could not be deleted."}
                             404 {:schema      ErrorResponseNotFound
                                  :description "A Tool with the given `tool-id` does not exist."}})
          :summary "Delete a Tool"
          :description "Deletes a tool, as long as it is not in use by any apps."
          (ok (admin-delete-tool user tool-id)))

  (GET "/:tool-id" []
       :path-params [tool-id :- ToolIdParam]
       :query [{:keys [user]} SecuredQueryParams]
       :responses (merge CommonResponses
                         {200 {:schema      ToolDetails
                               :description "The Tool details."}
                          404 {:schema      ErrorResponseNotFound
                               :description "The `tool-id` does not exist."}})
       :summary "Get a Tool"
       :description "This endpoint returns the details for one tool."
       (ok (get-tool user tool-id)))

  (PATCH "/:tool-id" []
         :path-params [tool-id :- ToolIdParam]
         :query [{:keys [user overwrite-public]} ToolUpdateParams]
         :body [body (describe ToolUpdateRequest "The Tool to update.")]
         :responses (merge CommonResponses
                           {200 {:schema      ToolDetails
                                 :description "The Tool details."}
                            400 {:schema      ErrorResponseNotWritable
                                 :description "The Tool is in use by public apps its container could not be updated."}
                            404 {:schema      ErrorResponseNotFound
                                 :description "The `tool-id` does not exist."}})
         :summary "Update a Tool"
         :description
   "This service updates a Tool definition in the DE.

**Note**: If the `container` object is omitted in the request, then existing container settings will not
be modified, but if the `container` object is present in the request, then all container settings must be
included in it. Any existing settings not included in the request's `container` object will be removed.

#### Danger Zone

    Do not update container settings that are in use by tools in public apps unless it is certain the new
    container settings will not break reproducibility for those apps.
    If required, the `overwrite-public` flag may be used to update these settings for public tools."
         (ok (admin-update-tool user overwrite-public (assoc body :id tool-id))))

  (GET "/:tool-id/apps" []
       :path-params [tool-id :- ToolIdParam]
       :query [params SecuredQueryParams]
       :responses (merge CommonResponses
                         {200 {:schema      AdminAppListing
                               :description "The listing of Apps using the given Tool."}
                          404 {:schema      ErrorResponseNotFound
                               :description "The `tool-id` does not exist."}})
       :summary "Get Apps by Tool"
       :description "This endpoint returns a listing of Apps using the given Tool."
       (ok (coerce! AdminAppListing
                    (app-listings/list-apps-by-tool current-user tool-id params true))))

  (POST "/:tool-id/container/devices" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body NewDevice]
         :return Device
         :summary "Adds Device To Tool Container"
         :description "Adds a new device to a tool container."
         (requester tool-id (add-tool-device tool-id body)))

  (DELETE "/:tool-id/container/devices/:device-id" []
           :path-params [tool-id :- ToolIdParam device-id :- DeviceIdParam]
           :query [params SecuredQueryParams]
           :return nil
           :summary "Delete a container device"
           :description "Deletes a device from the tool's container"
           (ok (delete-tool-device tool-id device-id)))

  (POST "/:tool-id/container/devices/:device-id/host-path" []
         :path-params [tool-id :- ToolIdParam device-id :- DeviceIdParam]
         :query [params SecuredQueryParams]
         :body [body DeviceHostPath]
         :return DeviceHostPath
         :summary "Update Tool Container Device Host Path"
         :description "This endpoint updates a device's host path for the tool's container."
         (requester tool-id (update-device-field tool-id device-id :host_path (:host_path body))))

  (POST "/:tool-id/container/devices/:device-id/container-path" []
         :path-params [tool-id :- ToolIdParam device-id :- DeviceIdParam]
         :query [params SecuredQueryParams]
         :body [body DeviceContainerPath]
         :return DeviceContainerPath
         :summary "Update Tool Device Container Path"
         :description "This endpoint updates a device's container path for the tool's container."
         (requester tool-id (update-device-field tool-id device-id :container_path (:container_path body))))

  (POST "/:tool-id/container/entrypoint" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body Entrypoint]
         :return Entrypoint
         :summary "Update Tool Container Entrypoint"
         :description (str
                        "This endpoint updates an entrypoint for the tool's container."
                        entrypoint-warning)
         (requester tool-id (update-settings-field tool-id :entrypoint (:entrypoint body))))

  (POST "/:tool-id/container/cpu-shares" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body CPUShares]
         :return CPUShares
         :summary "Update Tool Container CPU Shares"
         :description "This endpoint updates a the CPU shares for the tool's container."
         (requester tool-id (update-settings-field tool-id :cpu_shares (:cpu_shares body))))

  (POST "/:tool-id/container/memory-limit" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body MemoryLimit]
         :return MemoryLimit
         :summary "Update Tool Container Memory Limit"
         :description "This endpoint updates a the memory limit for the tool's container."
         (requester tool-id (update-settings-field tool-id :memory_limit (:memory_limit body))))

  (POST "/:tool-id/container/min-memory-limit" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body MinMemoryLimit]
         :return MinMemoryLimit
         :summary "Update Tool Container Minimum Memory Limit"
         :description "This endpoint updates the minimum memory limit for the tool's container."
         (requester tool-id (update-settings-field tool-id :min_memory_limit (:min_memory_limit body))))

  (POST "/:tool-id/container/min-cpu-cores" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body MinCPUCores]
         :return MinCPUCores
         :summary "Update Tool Container Minimum CPU Cores"
         :description "This endpoint updates the minimum number of CPU cores for the tool's container."
         (requester tool-id (update-settings-field tool-id :min_cpu_cores (:min_cpu_cores body))))

  (POST "/:tool-id/container/min-disk-space" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body MinDiskSpace]
         :return MinDiskSpace
         :summary "Update Tool Container Minimum Disk Space Requirement"
         :description "This endpoint updates the minimum amount of disk space required for the tool's container."
         (requester tool-id (update-settings-field tool-id :min_disk_space (:min_disk_space body))))

  (POST "/:tool-id/container/network-mode" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body NetworkMode]
         :return NetworkMode
         :summary "Update Tool Container Network Mode"
         :description "This endpoint updates a the network mode for the tool's container."
         (requester tool-id (update-settings-field tool-id :network_mode (:network_mode body))))

  (POST "/:tool-id/container/working-directory" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body WorkingDirectory]
         :return WorkingDirectory
         :summary "Update Tool Container Working Directory"
         :description "This endpoint updates the working directory for the tool's container."
         (requester tool-id (update-settings-field tool-id :working_directory (:working_directory body))))

  (POST "/:tool-id/container/name" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body ContainerName]
         :return ContainerName
         :summary "Update Tool Container Name"
         :description "This endpoint updates the container name for the tool's container."
         (requester tool-id (update-settings-field tool-id :name (:name body))))

  (POST "/:tool-id/container/volumes" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body NewVolume]
         :return Volume
         :summary "Tool Container Volume Information"
         :description (str
                        "This endpoint updates volume information for the container associated with a tool."
                        volumes-warning)
         (requester tool-id (add-tool-volume tool-id body)))

  (DELETE "/:tool-id/container/volumes/:volume-id" []
           :path-params [tool-id :- ToolIdParam volume-id :- VolumeIdParam]
           :query [params SecuredQueryParams]
           :return nil
           :summary "Delete Tool Container Volume"
           :description "Deletes a volume from a tool container."
           (ok (delete-tool-volume tool-id volume-id)))

  (POST "/:tool-id/container/volumes/:volume-id/host-path" []
         :path-params [tool-id :- ToolIdParam volume-id :- VolumeIdParam]
         :query [params SecuredQueryParams]
         :body [body VolumeHostPath]
         :return VolumeHostPath
         :summary "Update Tool Container Volume Host Path"
         :description (str
                        "This endpoint updates a volume host path for the tool's container."
                        volumes-warning)
         (requester tool-id (update-volume-field tool-id volume-id :host_path (:host_path body))))

  (POST "/:tool-id/container/volumes/:volume-id/container-path" []
         :path-params [tool-id :- ToolIdParam volume-id :- VolumeIdParam]
         :query [params SecuredQueryParams]
         :body [body VolumeContainerPath]
         :return VolumeContainerPath
         :summary "Update Tool Container Volume Container Path"
         :description (str
                        "This endpoint updates a volume container path for the tool's container."
                        volumes-warning)
         (requester tool-id (update-volume-field tool-id volume-id :container_path (:container_path body))))

  (PUT "/:tool-id/container/volumes-from" []
         :path-params [tool-id :- ToolIdParam]
         :query [params SecuredQueryParams]
         :body [body NewVolumesFrom]
         :return VolumesFrom
         :summary "Adds A Volume Host Container"
         :description (str
                        "Adds a new container from which the tool container will bind mount volumes."
                        volumes-warning)
         (requester tool-id (add-tool-volumes-from tool-id body)))

  (DELETE "/:tool-id/container/volumes-from/:volumes-from-id" []
           :path-params [tool-id :- ToolIdParam volumes-from-id :- VolumesFromIdParam]
           :query [params SecuredQueryParams]
           :return nil
           :summary "Delete Tool Container Volumes From Information"
           :description "Deletes a container name that the tool container should import volumes from."
           (ok (delete-tool-volumes-from tool-id volumes-from-id)))

  (PUT "/:tool-id/integration-data/:integration-data-id" []
        :path-params [tool-id :- ToolIdParam integration-data-id :- IntegrationDataIdPathParam]
        :query [params SecuredQueryParams]
        :return IntegrationData
        :summary "Update the Integration Data Record for a Tool"
        :description "This service allows administrators to change the integration data record
        associated with a tool."
        (ok (apps/update-tool-integration-data current-user de-system-id tool-id integration-data-id)))

  (POST "/:tool-id/publish" []
        :path-params [tool-id :- ToolIdParam]
        :query [params SecuredQueryParams]
        :body [body (describe ToolUpdateRequest "The Tool to update.")]
        :responses (merge CommonResponses
                          {200 {:schema      ToolDetails
                                :description "The Tool details."}
                           400 {:schema      ErrorResponseNotWritable
                                :description "The Tool is already public."}
                           404 {:schema      ErrorResponseNotFound
                                :description "The `tool-id` does not exist."}})
        :summary "Make a Private Tool Public"
        :description "This service makes a Private Tool public and available to all users.
        The request body fields are optional and allow the admin to make updates to the tool in the same request."
        (ok (admin-publish-tool current-user (assoc body :id tool-id)))))
