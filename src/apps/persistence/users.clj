(ns apps.persistence.users
  (:use [kameleon.uuids :only [uuidify]]
        [korma.core :exclude [update]])
  (:require [korma.core :as sql])
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
  "Recrds when a user logs into the DE."
  [user-id ip-address user-agent]
  (insert :logins
          (values {:user_id    user-id
                   :ip_address ip-address
                   :user_agent user-agent})))

(defn record-login
  "Records when a user logs into the DE. Returns the recorded login time."
  [username ip-address user-agent]
  (-> (insert-login-record (get-user-id username) ip-address user-agent)
      (:login_time)
      (.getTime)))

(defn record-logout
  "Records when a user logs out of the DE."
  [username ip-address login-time]
  (sql/update :logins
              (set-fields {:logout_time (sqlfn :now)})
              (where {:user_id                                       (get-user-id username)
                      :ip_address                                    ip-address})
              (where {(sqlfn :date_trunc "milliseconds" :login_time) (Timestamp. login-time)})))
