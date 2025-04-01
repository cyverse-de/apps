(ns apps.persistence.oauth
  "Functions to use for storing and retrieving OAuth access tokens."
  (:require [apps.util.pgp :as pgp]
            [korma.core :as sql]
            [slingshot.slingshot :refer [throw+]])
  (:import [java.util UUID]))

(defn- user-id-subselect
  "Returns a subselect statement to find a user ID."
  [username]
  (sql/subselect :users (sql/fields :id) (sql/where {:username username})))

(defn- replace-access-token
  "Replaces an existing access token in the database."
  [api-name username expires-at refresh-token access-token]
  (sql/update :access_tokens
              (sql/set-fields {:token         (pgp/encrypt access-token)
                               :expires_at    expires-at
                               :refresh_token (pgp/encrypt refresh-token)})
              (sql/where {:webapp  api-name
                          :user_id (user-id-subselect username)})))

(defn- insert-access-token
  "Inserts a new access token into the database."
  [api-name username expires-at refresh-token access-token]
  (sql/insert :access_tokens
              (sql/values {:webapp        api-name
                           :user_id       (user-id-subselect username)
                           :token         (pgp/encrypt access-token)
                           :expires_at    expires-at
                           :refresh_token (pgp/encrypt refresh-token)})))

(defn- decrypt-tokens
  "Decrypts access and refresh tokens retrieved from the database."
  [token-info]
  (when-not (nil? token-info)
    (-> token-info
        (update-in [:access-token] pgp/decrypt)
        (update-in [:refresh-token] pgp/decrypt))))

(defn get-access-token
  "Retrieves an access code from the database."
  [api-name username]
  (->> (sql/select [:access_tokens :t]
                   (sql/join [:users :u] {:t.user_id :u.id})
                   (sql/fields [:t.webapp        :webapp]
                               [:t.expires_at    :expires-at]
                               [:t.refresh_token :refresh-token]
                               [:t.token         :access-token])
                   (sql/where {:u.username username
                               :t.webapp   api-name}))
       (first)
       (decrypt-tokens)))

(defn has-access-token
  "Determines whether a user has an access token for an API."
  [api-name username]
  (seq (get-access-token api-name username)))

(defn store-access-token
  "Stores information about an OAuth access token in the database."
  [api-name username {:keys [expires-at refresh-token access-token]}]
  (if (has-access-token api-name username)
    (replace-access-token api-name username expires-at refresh-token access-token)
    (insert-access-token api-name username expires-at refresh-token access-token)))

(defn remove-access-token
  "Removes an OAuth access token from the database."
  [api-name username]
  (sql/delete :access_tokens
              (sql/where {:webapp  api-name
                          :user_id (sql/subselect :users
                                                  (sql/fields :id)
                                                  (sql/where {:username username}))})))

(defn- remove-prior-authorization-requests
  "Removes any previous OAuth authorization requests for the user."
  [username]
  (sql/delete :authorization_requests
              (sql/where {:user_id (user-id-subselect username)})))

(defn- insert-authorization-request
  "Inserts information about a new authorization request into the database."
  [id username state-info]
  (sql/insert :authorization_requests
              (sql/values {:id         id
                           :user_id    (user-id-subselect username)
                           :state_info state-info})))

(defn store-authorization-request
  "Stores state information for an OAuth authorization request."
  [username state-info]
  (let [id (UUID/randomUUID)]

    ;; Removing existing authorization requests from the database prior to inserting a new one is causing a
    ;; race condition where the authorization request that the user is actually using is being deleted before
    ;; the OAuth2 callback comes in. Ultimately, we're going to want to add timestamps to the database table
    ;; and periodically purge expired authorization requests. In the meantime, however, we can alleviate this
    ;; problem by simply not removing prior authorization requests from the database. This will cause some
    ;; cruft to accumulate in the database, but there won't be enough of it to cause any problems.
    ;;
    ;; (remove-prior-authorization-requests username)

    (insert-authorization-request id username state-info)
    (str id)))

(defn- get-authorization-request
  "Gets authorization request information from the database."
  [id]
  (first (sql/select [:authorization_requests :r]
                     (sql/join [:users :u] {:r.user_id :u.id})
                     (sql/fields [:u.username :username]
                                 [:r.state_info :state-info])
                     (sql/where {:r.id id}))))

(defn retrieve-authorization-request-state
  "Retrieves an authorization request for a given UUID."
  [id username]
  (let [id  (if (string? id) (UUID/fromString id) id)
        req (get-authorization-request id)]
    (when (nil? req)
      (throw+ {:type  :clojure-commons.exception/bad-request-field
               :error (str "authorization request " id " not found")}))
    (when (not= (:username req) username)
      (throw+ {:type  :clojure-commons.exception/bad-request-field
               :error (str "wrong user for authorization request " id)}))
    (remove-prior-authorization-requests username)
    (:state-info req)))
