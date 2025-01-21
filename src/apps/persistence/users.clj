(ns apps.persistence.users
  (:use [clojure-commons.core :only [remove-nil-values]]
        [kameleon.uuids :only [uuidify]]
        [korma.core :exclude [update]])
  (:require [korma.core :as sql]
            [apps.util.conversions :refer [long->timestamp date->long]])
  (:import [java.sql Timestamp]))

(defn- user-base-query
  []
  (-> (select* :users)
      (fields :id :username)))

(defn by-id
  [ids]
  (-> (user-base-query)
      (where {:id [in (mapv uuidify ids)]})
      (select)))

(defn for-username
  [username]
  (-> (user-base-query)
      (where {:username username})
      (select)
      (first)))

(defn get-existing-user-id
  "Gets an existing user identifier for a fully qualified username. Returns nil if the
   username isn't found in the database."
  [username]
  ((comp :id first)
   (select :users (where {:username username}))))

(defn- get-new-user-id
  "Gets a new user identifier for a fully qualified username."
  [username]
  (insert :users (values {:username username}))
  (get-existing-user-id username))

(defn get-user-id
  "Gets the internal user identifier for a fully qualified username.  A new
   entry will be added if the user doesn't already exist in the database."
  [username]
  (or (get-existing-user-id username)
      (get-new-user-id username)))

(defn- insert-login-record
  "Records when a user logs into the DE."
  [user-id ip-address session-id login-time]
  (insert :logins
          (values (remove-nil-values
                   {:user_id    user-id
                    :ip_address ip-address
                    :session_id (uuidify session-id)
                    :login_time (long->timestamp login-time)}))))

(defn- get-login-record
  "Gets an existing login record that matches all fields"
  [user-id ip-address session-id login-time]
  (first
    (select :logins
            (where {:user_id user-id
                    :ip_address ip-address
                    :session_id (uuidify session-id)
                    :login_time (long->timestamp login-time)}))))

(defn- upsert-login-record
  "Records a user login, if at least one field differs from an existing entry."
  [user-id ip-address session-id login-time]
  (or (get-login-record user-id ip-address session-id login-time)
      (insert-login-record user-id ip-address session-id login-time)))

(defn record-login
  "Records when a user logs into the DE. Returns the recorded login time."
  [username ip-address session-id login-time]
  (-> (upsert-login-record (get-user-id username) ip-address session-id login-time)
      (:login_time)
      (.getTime)))

(defn list-logins
  "Lists a number of the most recent logins for a user"
  [username query-limit]
  (->> (select :logins
               (where {:user_id (get-user-id username)})
               (order :login_time :DESC)
               (limit (or query-limit 5)))
       (mapv (fn [login]
               (select-keys
                 (update login :login_time date->long)
                 [:ip_address :login_time])))))
