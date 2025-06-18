(ns apps.routes.admin.tool-requests
  (:require [apps.metadata.tool-requests
             :refer [delete-tool-request
                     delete-tool-request-status-code
                     get-tool-request
                     list-tool-requests
                     update-tool-request]]
            [apps.routes.params :refer [SecuredQueryParams]]
            [apps.routes.schemas.tool :refer [ToolRequestListingParams]]
            [apps.user :refer [current-user]]
            [apps.util.config :as config]
            [common-swagger-api.schema
             :refer [context
                     defroutes
                     DELETE
                     GET
                     POST]]
            [common-swagger-api.schema.tools :as schema]
            [common-swagger-api.schema.tools.admin :as admin-schema]
            [ring.util.http-response :refer [ok]]))

(defroutes admin-tool-requests
  (GET "/" []
    :query [params ToolRequestListingParams]
    :return schema/ToolRequestListing
    :summary schema/ToolInstallRequestListingSummary
    :description admin-schema/ToolInstallRequestListingDocs
    (ok (list-tool-requests params)))

  (DELETE "/status-codes/:status-code-id" []
    :path-params [status-code-id :- schema/ToolRequestStatusCodeId]
    :query [params SecuredQueryParams]
    :summary admin-schema/ToolInstallRequestStatusCodeDeleteSummary
    :description admin-schema/ToolInstallRequestStatusCodeDeleteDocs
    (delete-tool-request-status-code status-code-id)
    (ok))

  (context "/:request-id" []
    :path-params [request-id :- schema/ToolRequestIdParam]

    (DELETE "/" []
      :query [params SecuredQueryParams]
      :summary admin-schema/ToolInstallRequestDeleteSummary
      :description admin-schema/ToolInstallRequestDeleteDocs
      (delete-tool-request request-id)
      (ok))

    (GET "/" []
      :query [params SecuredQueryParams]
      :return schema/ToolRequestDetails
      :summary admin-schema/ToolInstallRequestDetailsSummary
      :description admin-schema/ToolInstallRequestDetailsDocs
      (ok (get-tool-request request-id)))

    (POST "/status" []
      :query [params SecuredQueryParams]
      :body [body admin-schema/ToolRequestStatusUpdate]
      :return schema/ToolRequestDetails
      :summary admin-schema/ToolInstallRequestStatusUpdateSummary
      :description admin-schema/ToolInstallRequestStatusUpdateDocs
      (ok (update-tool-request request-id (config/uid-domain) current-user body)))))
