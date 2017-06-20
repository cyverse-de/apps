(ns apps.routes.oauth
  (:use [common-swagger-api.schema]
        [apps.routes.params]
        [apps.routes.schemas.oauth]
        [apps.user :only [current-user load-user]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.oauth :as oauth]))

(defroutes oauth
  (GET "/access-code/:api-name" []
    :path-params [api-name :- ApiName]
    :query       [params OAuthCallbackQueryParams]
    :return      OAuthCallbackResponse
    :summary     "Obtain an OAuth access token for an authorization code."
    (ok (oauth/get-access-token api-name params)))

  (context "/token-info/:api-name" []
    :path-params [api-name :- ApiName]

    (GET "/" []
      :query   [params SecuredQueryParams]
      :return  TokenInfo
      :summary "Return information about an OAuth access token, not including the token itself."
      (ok (oauth/get-token-info api-name current-user)))

    (DELETE "/" []
      :query   [params SecuredQueryParams]
      :summary "Remove a user's OAuth access token from the DE."
      (oauth/remove-token-info api-name current-user)
      (ok)))

  (GET "/redirect-uris" []
    :query [params SecuredQueryParams]
    :return RedirectUrisResponse
    :summary "Return a set of OAuth redirect URIs if the user hasn't authenticated with the remote API yet."
    (ok (oauth/get-redirect-uris current-user))))

(defroutes admin-oauth
  (context "/token-info/:api-name" []
    :path-params [api-name :- ApiName]

    (GET "/" []
      :query   [params SecuredProxyQueryParams]
      :return  AdminTokenInfo
      :summary "Return information about an OAuth access token."
      (ok (oauth/get-admin-token-info api-name (if (:proxy-user params)
                                                 (load-user (:proxy-user params))
                                                 current-user))))

    (DELETE "/" []
      :query   [params SecuredProxyQueryParams]
      :summary "Remove a user's OAuth access token from the DE."
      (oauth/remove-token-info api-name (if (:proxy-user params)
                                          (load-user (:proxy-user params))
                                          current-user))
      (ok))))
