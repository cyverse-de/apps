(ns apps.routes.integration-data
  (:require
   [apps.routes.params :refer [IntegrationDataSearchParams SecuredQueryParams]]
   [apps.service.integration-data :as integration-data]
   [apps.user :refer [current-user]]
   [common-swagger-api.schema :refer [defroutes DELETE describe GET POST PUT]]
   [common-swagger-api.schema.integration-data :as schema]
   [ring.util.http-response :refer [ok]]))

(defroutes admin-integration-data
  (GET "/" []
    :query [params IntegrationDataSearchParams]
    :summary "List Integration Data Records"
    :return schema/IntegrationDataListing
    :description "This service allows administrators to list and search for integration data in the DE apps
    database. Entries may be filtered by name or email address using the `search` query parameter. They may
    also be sorted by username, email address or name using the `:sort-field` query parameter."
    (ok (integration-data/list-integration-data current-user params)))

  (POST "/" []
    :query [params SecuredQueryParams]
    :summary "Add an Integration Data Record"
    :body [body (describe schema/IntegrationDataRequest "The integration data record to add.")]
    :return schema/IntegrationData
    :description "This service allows administrators to add a new integration data record to the DE apps database."
    (ok (integration-data/add-integration-data current-user body)))

  (GET "/:integration-data-id" []
    :path-params [integration-data-id :- schema/IntegrationDataIdPathParam]
    :query [params SecuredQueryParams]
    :summary "Get an Integration Data Record"
    :return schema/IntegrationData
    :description "This service allows administrators to retrieve information about an integration data record."
    (ok (integration-data/get-integration-data current-user integration-data-id)))

  (PUT "/:integration-data-id" []
    :path-params [integration-data-id :- schema/IntegrationDataIdPathParam]
    :query [params SecuredQueryParams]
    :summary "Update an Integration Data Record"
    :body [body (describe schema/IntegrationDataUpdate "The updated integration data information.")]
    :return schema/IntegrationData
    :description "This service allows administrators to update integration data records in the DE apps database."
    (ok (integration-data/update-integration-data current-user integration-data-id body)))

  (DELETE "/:integration-data-id" []
    :path-params [integration-data-id :- schema/IntegrationDataIdPathParam]
    :query [params SecuredQueryParams]
    :summary "Delete an Integration Data Record"
    :description "This service allows administrators to delete individual integration data records."
    (integration-data/delete-integration-data current-user integration-data-id)
    (ok)))
