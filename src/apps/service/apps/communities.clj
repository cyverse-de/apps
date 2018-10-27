(ns apps.service.apps.communities
  (:require [apps.clients.iplant-groups :as groups]
            [apps.clients.metadata :as metadata-client]
            [apps.util.config :as config]
            [cheshire.core :as json]
            [clojure.set :as sets]
            [clojure-commons.exception-util :as exception-util]))

(defn- get-community-admin-set
  [username name]
  (->> (groups/get-community-admins username name)
       :members
       (mapv :id)
       set))

(defn- validate-community-admin
  [username name]
  (when-not (contains? (get-community-admin-set username name) username)
    (exception-util/forbidden "User is not an admin of that community"
                              :user      username
                              :community name)))

(defn- validate-avu-community-admins
  [username community-avus]
  (doseq [community-name (->> community-avus
                              (group-by :value)
                              keys)]
    (validate-community-admin username community-name)))

(defn- community-admin-update-avus
  "add/update only community AVUs as an admin"
  [username app-id {:keys [avus] :as request} admin?]
  (when-not admin?
    (validate-avu-community-admins username avus))
  (metadata-client/update-avus username app-id (json/encode request)))

(defn- community-admin-remove-avus
  "remove only community AVUs as an admin"
  [username app-id {:keys [avus]} admin?]
  (when-not admin?
    (validate-avu-community-admins username avus))

  (let [community-avu-set (->> avus
                               (map #(select-keys % [:attr :value :unit]))
                               set
                               seq)]
    (metadata-client/delete-avus username [app-id] community-avu-set)))

(defn add-app-to-communities
  [{username :shortUsername} app-id {:keys [avus]} admin?]
  (if-let [community-avus (get (group-by :attr avus)
                               (config/workspace-metadata-communities-attr))]
    (community-admin-update-avus username app-id {:avus community-avus} admin?)
    (exception-util/bad-request "No community metadata found in request")))

(defn remove-app-from-communities
  [{username :shortUsername} app-id {:keys [avus]} admin?]
  (if-let [community-avus (get (group-by :attr avus)
                               (config/workspace-metadata-communities-attr))]
    (community-admin-remove-avus username app-id {:avus community-avus} admin?)
    (exception-util/bad-request "No community metadata found in request")))
