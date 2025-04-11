(ns apps.service.users
  (:require
   [apps.persistence.users :as up]
   [apps.service.oauth :as oauth]
   [apps.util.conversions :refer [remove-nil-vals]]))

(defn by-id
  [{:keys [ids]}]
  {:users (mapv remove-nil-vals (up/by-id ids))})

(defn authenticated
  [{:keys [username]}]
  (remove-nil-vals (up/for-username username)))

(defn login
  [{:keys [username] :as current-user} {:keys [ip-address login-time session-id]}]
  {:login_time (up/record-login username ip-address session-id login-time)
   :auth_redirect (oauth/get-redirect-uris current-user)})

(defn list-logins
  [{:keys [username] :as _current-user} {:keys [limit] :or {limit 5}}]
  {:logins (mapv remove-nil-vals (up/list-logins username limit))})
