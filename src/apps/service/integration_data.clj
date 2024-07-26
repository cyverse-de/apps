(ns apps.service.integration-data
  (:use [apps.util.db :only [transaction]]
        [medley.core :only [remove-vals]])
  (:require [apps.persistence.app-metadata :as amp]
            [apps.persistence.tools :as tools-db]
            [apps.service.apps.de.validation :as app-validation]
            [apps.users :refer [append-username-suffix]]
            [clojure.string :as string]
            [clojure-commons.exception-util :as cxu]))

(defn- sort-field-to-db-field [sort-field]
  (cond (= sort-field :name)  :integrator_name
        (= sort-field :email) :integrator_email
        :else                 sort-field))

(defn- format-integration-data [{:keys [id username] email :integrator_email name :integrator_name}]
  (->> {:id       id
        :username (when-not (nil? username) (string/replace username #"@.*" ""))
        :email    email
        :name     name}
       (remove-vals nil?)))

(defn list-integration-data [_ {:keys [search limit offset sort-field sort-dir]}]
  (let [sort-field (sort-field-to-db-field sort-field)]
    (transaction
     {:integration_data
      (mapv format-integration-data (amp/list-integration-data search limit offset sort-field (keyword sort-dir)))

      :total
      (amp/count-integration-data search)})))

(defn- duplicate-username [username]
  (cxu/bad-request (str "user " username " already has an integration data record")))

(defn- duplicate-email [email]
  (cxu/bad-request (str "email address " email " already has an integration data record")))

(defn- not-found [id]
  (cxu/not-found (str "integration data record " id " does not exist")))

(defn- integration-data-record-used [type id used-by-ids]
  (cxu/bad-request (str "integration data record " id " is used by one or more " type ": " used-by-ids)))

(def ^:private used-by-tools (partial integration-data-record-used "tools"))
(def ^:private used-by-apps (partial integration-data-record-used "apps"))

(defn add-integration-data [_ {:keys [username name email]}]
  (let [qualified-username (when username (append-username-suffix username))]
    (cond
      (and username (amp/get-integration-data-by-username qualified-username))
      (duplicate-username username)

      (amp/get-integration-data-by-email email)
      (duplicate-email email))

    (let [id (:id (amp/get-integration-data qualified-username email name))]
      (format-integration-data (amp/get-integration-data-by-id id)))))

(defn get-integration-data [_ id]
  (let [integration-data (amp/get-integration-data-by-id id)]
    (if-not (nil? integration-data)
      (format-integration-data integration-data)
      (not-found id))))

(defn update-integration-data [_ id {:keys [name email]}]
  (let [integration-data (amp/get-integration-data-by-id id)]
    (cond
      (nil? integration-data)
      (not-found id)

      ;; The database already contains duplicates, so we're not going to complain unless the email
      ;; address is being changed.
      (and (not= (:integrator_email integration-data) email)
           (amp/get-integration-data-by-email email))
      (duplicate-email email)))

  (amp/update-integration-data id name email)
  (format-integration-data (amp/get-integration-data-by-id id)))

(defn delete-integration-data [_ id]
  (let [integration-data (amp/get-integration-data-by-id id)
        tool-ids         (amp/get-tool-ids-by-integration-data-id id)
        app-ids          (amp/get-app-ids-by-integration-data-id id)]
    (cond
      (nil? integration-data)
      (not-found id)

      (seq tool-ids)
      (used-by-tools id tool-ids)

      (seq app-ids)
      (used-by-apps id app-ids))

    (amp/delete-integration-data id)))

(defn get-integration-data-for-app [_ app-id]
  (if-let [integration-data (amp/get-integration-data-by-app-id app-id)]
    (format-integration-data integration-data)
    (cxu/not-found (str "no integration data found for app: " app-id))))

(defn get-integration-data-for-app-version [_ app-id version-id]
  (app-validation/validate-app-version-existence app-id version-id)
  (if-let [integration-data (amp/get-integration-data-by-app-version-id version-id)]
    (format-integration-data integration-data)
    (cxu/not-found (str "no integration data found for app version: " version-id))))

(defn get-integration-data-for-tool [_ tool-id]
  (if-let [integration-data (amp/get-integration-data-by-tool-id tool-id)]
    (format-integration-data integration-data)
    (cxu/not-found (str "no integration data found for tool: " tool-id))))

(defn update-integration-data-for-app [_ app-id integration-data-id]
  (app-validation/validate-app-existence app-id)
  (if-let [integration-data (amp/get-integration-data-by-id integration-data-id)]
    (do (amp/update-app-integration-data app-id integration-data-id)
        (format-integration-data integration-data))
    (not-found integration-data-id)))

(defn update-integration-data-for-app-version [_ app-id version-id integration-data-id]
  (app-validation/validate-app-version-existence app-id version-id)
  (if-let [integration-data (amp/get-integration-data-by-id integration-data-id)]
    (do (amp/update-app-version-integration-data version-id integration-data-id)
        (format-integration-data integration-data))
    (not-found integration-data-id)))

(defn update-integration-data-for-tool [_ tool-id integration-data-id]
  (tools-db/get-tool tool-id)
  (if-let [integration-data (amp/get-integration-data-by-id integration-data-id)]
    (do (amp/update-tool-integration-data tool-id integration-data-id)
        (format-integration-data integration-data))
    (not-found integration-data-id)))
