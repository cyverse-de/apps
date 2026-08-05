(ns apps.service.apps.communities
  (:require [apps.clients.groups :as groups]
            [apps.clients.metadata :as metadata-client]
            [apps.util.config :as config]
            [cheshire.core :as json]
            [clojure-commons.exception-util :as exception-util]
            [clojure.tools.logging :as log]))

(defn- resolve-community
  "Resolves a community identifier, failing with a 404 if it names nothing.
   Every request goes through this, an administrator's included: the stored tag
   is what a community listing matches on, so accepting one that resolves to no
   community writes a tag no app will ever be found by."
  [identifier]
  (or (groups/lookup-community identifier)
      (exception-util/not-found "No such community" :community identifier)))

(defn- community-admin-ids
  [community-id]
  (set (mapv :id (:members (groups/list-community-admins community-id)))))

(defn get-community-admin-set
  [identifier]
  (community-admin-ids (:id (resolve-community identifier))))

(defn- validate-community-admin
  [username {community-id :id community-name :name}]
  (when-not (contains? (community-admin-ids community-id) username)
    (exception-util/forbidden "User is not an admin of that community"
                              :user      username
                              :community community-name)))

(defn filter-community-avus
  [avus]
  (get (group-by :attr avus)
       (config/workspace-metadata-communities-attr)))

(defn extract-community-identifiers
  [avus]
  (->> avus
       filter-community-avus
       (map :value)
       distinct))

(defn- request-community-identifiers
  "The communities a request names. `community_ids` is the current form; the AVU
   list is what a browser holding a stale bundle still sends, and is accepted
   until those bundles are gone."
  [{:keys [community_ids avus]}]
  (or (seq community_ids)
      (when-let [identifiers (seq (extract-community-identifiers avus))]
        (log/warn "request names communities with the legacy AVU shape instead of community_ids;"
                  "the requesting browser is probably running a stale bundle")
        identifiers)))

(defn- resolve-request-communities
  [username request admin?]
  (let [identifiers (or (request-community-identifiers request)
                        (exception-util/bad-request "No communities found in request"))
        communities (mapv resolve-community identifiers)]
    (when-not admin?
      (dorun (map (partial validate-community-admin username) communities)))
    communities))

(defn- community-avus
  "The AVUs for a set of communities. The value is the community's ID: a name can
   change, and every app tagged with the old one would drop out of its listing."
  [communities]
  (mapv (fn [{community-id :id}]
          {:attr  (config/workspace-metadata-communities-attr)
           :value community-id
           :unit  ""})
        communities))

(defn add-app-to-communities
  [{username :shortUsername} app-id request admin?]
  (let [communities (resolve-request-communities username request admin?)]
    (metadata-client/update-avus username app-id
                                 (json/encode {:avus (community-avus communities)}))))

(defn remove-app-from-communities
  [{username :shortUsername} app-id request admin?]
  (let [communities (resolve-request-communities username request admin?)]
    (metadata-client/delete-avus username [app-id] (seq (community-avus communities)))))

(defn remove-app-from-community
  [user app-id community-id admin?]
  (remove-app-from-communities user app-id {:community_ids [community-id]} admin?))
