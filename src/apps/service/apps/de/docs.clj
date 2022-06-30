(ns apps.service.apps.de.docs
  (:use [slingshot.slingshot :only [throw+]])
  (:require [apps.persistence.app-documentation :as dp]
            [apps.persistence.app-metadata :as ap]
            [apps.service.apps.de.validation :as de-validation]
            [apps.validation :as v]
            [clojure-commons.validators :as cv]))

(defn- get-references
  "Returns a list of references from the database for the given app version ID."
  [app-version-id]
  (map :reference_text (dp/get-app-references app-version-id)))

(defn- get-app-version-docs*
  "Retrieves app documentation."
  [app-version-id]
  (if-let [docs (dp/get-documentation app-version-id)]
    (assoc docs :references (get-references app-version-id))
    (throw+ {:type           :clojure-commons.exception/not-found
             :error          "App documentation not found"
             :app_version_id app-version-id})))

(defn get-app-version-docs
  "Retrieves documentation details for a specific version of an App."
  ([user app-id version-id]
   (get-app-version-docs user app-id version-id false))
  ([user app-id version-id admin?]
   (de-validation/verify-app-permission user (ap/get-app-version app-id version-id) "read" admin?)
   (get-app-version-docs* version-id)))

(defn get-app-docs
  "Retrieves documentation details for the latest version of an App."
  [user app-id]
  (get-app-version-docs user app-id (ap/get-app-latest-version app-id) false))

(defn edit-app-docs
  "Updates the latest version of an App's documentation and modified details in the database."
  [{:keys [username]} app-id {docs :documentation}]
  (let [app-version-id (ap/get-app-latest-version app-id)]
    (when (get-app-version-docs* app-version-id)
      (dp/edit-documentation (v/get-valid-user-id username) docs app-version-id))
    (get-app-version-docs* app-version-id)))

(defn owner-edit-app-docs
  "Updates the latest version of an app's documentation in the database
   if the user has permission to edit the app."
  [user app-id docs]
  (let [app (ap/get-app app-id)]
    (when-not (cv/user-owns-app? user app)
      (de-validation/verify-app-permission user app "write")))
  (edit-app-docs user app-id docs))

(defn add-app-docs
  "Adds documentation to the latest version of an App."
  [{:keys [username]} app-id {docs :documentation}]
  (let [app-version-id (ap/get-app-latest-version app-id)]
    (when (dp/get-documentation app-version-id)
      (throw+ {:type           :clojure-commons.exception/exists
               :error          "App already has documentation"
               :app_version_id app-version-id}))
    (dp/add-documentation (v/get-valid-user-id username) docs app-version-id)
    (get-app-version-docs* app-version-id)))

(defn owner-add-app-docs
  "Adds documentation to the latest version of an App if the user has permission to edit the app."
  [user app-id docs]
  (let [app (ap/get-app app-id)]
    (when-not (cv/user-owns-app? user app)
      (de-validation/verify-app-permission user (ap/get-app app-id) "write")))
  (add-app-docs user app-id docs))

(defn has-docs?
  "Determines whether the latest version of an App has docs already."
  [app-id]
  (when (dp/get-documentation (ap/get-app-latest-version app-id))
    true))
