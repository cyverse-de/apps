(ns apps.routes.apps.metadata
  (:use [common-swagger-api.routes]
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps :only [AppIdParam]]
        [common-swagger-api.schema.metadata
         :only [AvuList
                AvuListRequest
                SetAvuRequest]]
        [apps.routes.params :only [SecuredQueryParams]]
        [apps.user :only [current-user]]
        [ring.util.http-response :only [ok]])
  (:require [common-swagger-api.schema.apps.metadata :as schema]
            [apps.metadata.avus :as avus]
            [apps.util.service :as service]
            [compojure.route :as route]))

(defroutes app-metadata

  (GET "/" []
       :path-params [app-id :- AppIdParam]
       :query [params SecuredQueryParams]
       :return AvuList
       :summary schema/AppMetadataListingSummary
       :description schema/AppMetadataListingDocs
       (ok (avus/list-avus current-user app-id false)))

  (POST "/" [:as {:keys [body]}]
        :path-params [app-id :- AppIdParam]
        :query [params SecuredQueryParams]
        :body [body AvuListRequest]
        :return AvuList
        :summary schema/AppMetadataUpdateSummary
        :description schema/AppMetadataUpdateDocs
        (ok (avus/update-avus current-user app-id body false)))

  (PUT "/" [:as {:keys [body]}]
       :path-params [app-id :- AppIdParam]
       :query [params SecuredQueryParams]
       :body [body SetAvuRequest]
       :return AvuList
       :summary schema/AppMetadataSetSummary
       :description schema/AppMetadataSetDocs
       (ok (avus/set-avus current-user app-id body false)))

  (undocumented (route/not-found (service/unrecognized-path-response))))

(defroutes admin-app-metadata

  (GET "/" []
       :path-params [app-id :- AppIdParam]
       :query [params SecuredQueryParams]
       :return AvuList
       :summary "View all Metadata AVUs"
       :description (str
"Lists all AVUs associated with the app."
(get-endpoint-delegate-block
  "metadata"
  "GET /avus/{target-type}/{target-id}")
"Where `{target-type}` is `app`.")
       (ok (avus/list-avus current-user app-id true)))

  (POST "/" [:as {:keys [body]}]
        :path-params [app-id :- AppIdParam]
        :query [params SecuredQueryParams]
        :body [body AvuListRequest]
        :return AvuList
        :summary "Add/Update Metadata AVUs"
        :description (str
"Adds or updates Metadata AVUs on the app.
 The app's name must not duplicate the name of any other app (visible to the requesting user)
 that also has any of the ontology hierarchy AVUs given in the request."
(get-endpoint-delegate-block
  "metadata"
  "POST /avus/filter-targets")
(get-endpoint-delegate-block
  "metadata"
  "POST /avus/{target-type}/{target-id}")
"Where `{target-type}` is `app`.")
        (ok (avus/update-avus current-user app-id body true)))

  (PUT "/" [:as {:keys [body]}]
       :path-params [app-id :- AppIdParam]
       :query [params SecuredQueryParams]
       :body [body SetAvuRequest]
       :return AvuList
       :summary "Set Metadata AVUs"
       :description (str
"Sets Metadata AVUs on the app.
 The app's name must not duplicate the name of any other app (visible to the requesting user)
 that also has any of the ontology hierarchy AVUs given in the request."
(get-endpoint-delegate-block
  "metadata"
  "POST /avus/filter-targets")
(get-endpoint-delegate-block
  "metadata"
  "PUT /avus/{target-type}/{target-id}")
"Where `{target-type}` is `app`.")
       (ok (avus/set-avus current-user app-id body true)))

  (undocumented (route/not-found (service/unrecognized-path-response))))
