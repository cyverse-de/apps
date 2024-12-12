(ns apps.routes.schemas.user
  (:use [common-swagger-api.schema :only [describe]]
        [apps.routes.params :only [SecuredQueryParams]]
        [schema.core :only [defschema optional-key]])
  (:require [common-swagger-api.schema.sessions :as sessions-schema])
  (:import [java.util UUID]))

(defschema User
  {:id       (describe UUID "The DE's internal user identifier")
   :username (describe String "The user's iPlant username")})

(defschema Users
  {:users (describe [User] "The list of users")})

(defschema UserIds
  {:ids (describe [UUID] "The list of user IDs")})

(defschema LoginParams
  (merge SecuredQueryParams
         sessions-schema/IPAddrParam
         {(optional-key :session-id) (describe String "The session ID provided by the auth provider.")
          (optional-key :login-time) (describe Long "Login time as milliseconds since the epoch, provided by auth provider.")}))
