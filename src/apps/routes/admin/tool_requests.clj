(ns apps.routes.admin.tool-requests
  (:use [apps.metadata.tool-requests]
        [apps.routes.params :only [SecuredQueryParams]]
        [apps.routes.schemas.tool :only [ToolRequestListingParams]]
        [apps.user :only [current-user]]
        [common-swagger-api.schema]
        [ring.util.http-response :only [ok]])
  (:require [apps.util.config :as config]
            [common-swagger-api.schema.tools :as schema]
            [common-swagger-api.schema.tools.admin :as admin-schema]))

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
