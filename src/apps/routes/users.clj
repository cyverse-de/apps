(ns apps.routes.users
  (:use [common-swagger-api.schema]
        [apps.routes.params]
        [apps.routes.schemas.user]
        [apps.user :only [current-user]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.users :as users]
            [common-swagger-api.schema.sessions :as sessions-schema]))

(defroutes users
  (POST "/by-id" []
    :query [params SecuredQueryParams]
    :body [body (describe UserIds "The list of user IDs to look up.")]
    :return Users
    :summary "Look up usernames"
    :description "This endpoint returns usernames for internal user IDs."
    (ok (users/by-id body)))

  (GET "/authenticated" []
    :query [params SecuredQueryParams]
    :return User
    :summary "Get the Authenticated User"
    :description "This endpoint returns information about the authenticated user."
    (ok (users/authenticated current-user)))

  (POST "/login" []
    :query [params LoginParams]
    :return sessions-schema/LoginResponse
    :summary "User Login Service"
    :description "Terrain calls this service to record when a user logs in
          and to fetch user session info."
    (ok (users/login current-user params)))
  
  (GET "/logins" []
    :query [params ListLoginsParams]
    ;; XXX: :return something
    :summary "Login listing"
    :description "Fetch a listing of recent logins"
    (ok (users/list-logins current-user params))))
