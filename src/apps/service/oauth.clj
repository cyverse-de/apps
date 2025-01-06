(ns apps.service.oauth
  "Service implementations dealing with OAuth 2.0 authentication."
  (:use [apps.user :only [current-user]]
        [medley.core :only [remove-vals]]
        [slingshot.slingshot :only [throw+]])
  (:require [apps.persistence.oauth :as op]
            [apps.util.config :as config]
            [cemerick.url :as curl]
            [clojure-commons.exception-util :as cxu]
            [authy.core :as authy]))

(defn- build-authy-server-info
  "Builds the server info to pass to authy."
  [server-info token-callback]
  (assoc (dissoc server-info :api-name)
         :token-callback token-callback))

(def ^:private server-info-fn-for
  (memoize #(->> {:tapis (when (config/tapis-enabled) config/tapis-oauth-settings)}
                 (remove-vals nil?))))

(defn- get-server-info
  "Retrieves the server info for the given API name."
  [api-name]
  (if-let [server-info-fn ((server-info-fn-for) (keyword api-name))]
    (server-info-fn)
    (throw+ {:type  :clojure-commons.exception/bad-request-field
             :error (str "unknown API name: " api-name)})))

(defn get-access-token
  "Receives an OAuth authorization code and obtains an access token."
  [api-name {:keys [code state]}]
  (let [server-info    (get-server-info api-name)
        username       (:username current-user)
        state-info     (op/retrieve-authorization-request-state state username)
        token-callback (partial op/store-access-token api-name username)]
    (authy/get-access-token (build-authy-server-info server-info token-callback) code)
    {:state_info state-info}))

(defn- format-admin-token-info
  "Formats access token info for administrative endpoints."
  [token-info]
  (when token-info
    {:access_token  (:access-token token-info)
     :expires_at    (.. (:expires-at token-info) toInstant toEpochMilli)
     :refresh_token (:refresh-token token-info)
     :webapp        (:webapp token-info)}))

(defn- format-token-info
  "Formats access token info."
  [token-info]
  (when token-info
    (-> (format-admin-token-info token-info)
        (select-keys [:expires_at :webapp]))))

(defn get-token-info
  "Retrieves the user's token information, excluding the actual tokens, for an external API if it exists."
  [api-name {:keys [username]}]
  (or (format-token-info (op/get-access-token api-name username))
      (cxu/not-found "access token not found" :api_name api-name)))

(defn get-admin-token-info
  "Retrieves the user's token information for an external API if it exists."
  [api-name {:keys [username]}]
  (or (format-admin-token-info (op/get-access-token api-name username))
      (cxu/not-found "access token not found" :api_name api-name)))

(defn remove-token-info
  "Removes a user's token information if it exists."
  [api-name {:keys [username]}]
  (when-not (op/get-access-token api-name username)
    (cxu/not-found "access token not found" :api_name api-name))
  (op/remove-access-token api-name username))

(defn authorization-uri
  "Generates an authorization URI for a remote API."
  [{:keys [api-name] :as server-info} username state-info]
  (str (assoc (curl/url (:auth-uri server-info))
              :query {:response_type "code"
                      :client_id     (:client-key server-info)
                      :redirect_uri  (:redirect-uri server-info)
                      :state         (op/store-authorization-request username state-info)})))

(defn has-access-token
  "Determines whether or not a user has an access token for an external API."
  [{:keys [api-name] :as server-info} username]
  (seq (op/get-access-token api-name username)))

(defn- build-auth-uri
  "Builds an authorization URI for an external API if the user doesn't have an access token already."
  [server-info username]
  (when-not (has-access-token server-info username)
    (authorization-uri server-info username "")))

(defn get-redirect-uris
  "Retrieves the redirect URIs that can be used for a user to authenticate to a remote API."
  [{:keys [username]}]
  (let [build-auth-uri* (fn [[_ server-info-fn]] (build-auth-uri (server-info-fn) username))]
    (remove-vals nil? (into {} (map (juxt key build-auth-uri*) (server-info-fn-for))))))
