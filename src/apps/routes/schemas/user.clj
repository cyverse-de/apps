(ns apps.routes.schemas.user
  (:use [common-swagger-api.schema :only [describe]]
        [apps.routes.params :only [SecuredQueryParams]]
        [schema.core :only [defschema]])
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
         {:user-agent (describe String "The user agent obtained from the original request.")}))

(defschema LogoutParams
  (merge SecuredQueryParams
         sessions-schema/LogoutParams))
