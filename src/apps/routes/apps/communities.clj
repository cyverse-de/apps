(ns apps.routes.apps.communities
  (:require
   [apps.routes.params :refer [SecuredQueryParams]]
   [apps.service.apps.communities :as communities]
   [apps.user :refer [current-user]]
   [apps.util.service :as service]
   [common-swagger-api.routes :refer [get-endpoint-delegate-block]]
   [common-swagger-api.schema :refer [defroutes DELETE POST undocumented]]
   [common-swagger-api.schema.apps :refer [AppCategoryMetadataAddRequest AppCategoryMetadataDeleteRequest AppIdParam]]
   [common-swagger-api.schema.apps.communities :as schema]
   [common-swagger-api.schema.metadata :refer [AvuList]]
   [compojure.route :as route]
   [ring.util.http-response :refer [ok]]))

(defroutes app-community-tags

  (POST "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body AppCategoryMetadataAddRequest]
    :return AvuList
    :summary schema/AppCommunityMetadataAddSummary
    :description schema/AppCommunityMetadataAddDocs
    (ok (communities/add-app-to-communities current-user app-id body false)))

  (DELETE "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body AppCategoryMetadataDeleteRequest]
    :summary schema/AppCommunityMetadataDeleteSummary
    :description schema/AppCommunityMetadataDeleteDocs
    (ok (communities/remove-app-from-communities current-user app-id body false)))

  (undocumented (route/not-found (service/unrecognized-path-response))))

(defroutes admin-app-community-tags

  (POST "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body AppCategoryMetadataAddRequest]
    :return AvuList
    :summary "Add/Update Community Metadata AVUs"
    :description (str
                  "Adds or updates Community Metadata AVUs on the app."
                  (get-endpoint-delegate-block
                   "metadata"
                   "POST /avus/{target-type}/{target-id}")
                  "Where `{target-type}` is `app`."
                  " Please see the metadata service documentation for request information.")
    (ok (communities/add-app-to-communities current-user app-id body true)))

  (DELETE "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body AppCategoryMetadataDeleteRequest]
    :summary "Remove Community Metadata AVUs"
    :description (str
                  "Removes the given Community AVUs associated with an app."
                  (get-endpoint-delegate-block
                   "metadata"
                   "POST /avus/deleter"))
    (ok (communities/remove-app-from-communities current-user app-id body true)))

  (undocumented (route/not-found (service/unrecognized-path-response))))
