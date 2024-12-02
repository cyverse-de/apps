(ns apps.service.users
  (:use [apps.util.conversions :only [remove-nil-vals]])
  (:require [apps.service.oauth :as oauth]
            [apps.persistence.users :as up]))

(defn by-id
  [{:keys [ids]}]
  {:users (mapv remove-nil-vals (up/by-id ids))})

(defn authenticated
  [{:keys [username]}]
  (remove-nil-vals (up/for-username username)))

(defn login
  [{:keys [username] :as current-user} {:keys [ip-address]}]
  {:login_time (up/record-login username ip-address)
   :auth_redirect (oauth/get-redirect-uris current-user)})
