(ns apps.tools
  (:use [apps.containers :only [add-tool-container set-tool-container tool-container-info]]
        [apps.persistence.entities :only [tools]]
        [apps.util.conversions :only [remove-nil-vals]]
        [apps.validation :only [verify-tool-name-version validate-tool-not-used]]
        [korma.core :exclude [update]]
        [korma.db :only [transaction]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.permissions :as perms-client]
            [apps.persistence.tools :as persistence]
            [apps.persistence.tool-requests :as tool-req-db]
            [apps.metadata.tool-requests :as tool-requests]
            [apps.tools.permissions :as permissions]
            [apps.util.config :as config]
            [clojure-commons.exception-util :as exception-util]
            [clojure.tools.logging :as log]))

(defn format-tool-listing
  [perms public-tool-ids
   {:keys [id
           image_name
           image_deprecated
           image_tag
           image_url
           container_entrypoint
           implementor
           implementor_email
           tool_request_id
           tool_request_status]
    :as tool}]
  (-> tool
      (assoc :is_public      (contains? public-tool-ids id)
             :permission     (or (perms id) "")
             :implementation {:implementor       implementor
                              :implementor_email implementor_email}
             :container      (remove-nil-vals
                              {:entrypoint container_entrypoint
                               :image      (remove-nil-vals
                                            {:name       image_name
                                             :tag        image_tag
                                             :deprecated image_deprecated
                                             :url        image_url})})
             :tool_request   (when tool_request_id (remove-nil-vals
                                                     {:id     tool_request_id
                                                      :status tool_request_status})))
      (dissoc :container_entrypoint
              :image_name
              :image_deprecated
              :image_tag
              :image_url
              :implementor
              :implementor_email
              :tool_request_id
              :tool_request_status)
      remove-nil-vals))

(defn- filter-listing-tool-ids
  [all-tool-ids public-tool-ids {:keys [public] :as params}]
  (if (contains? params :public)
    (if public
      public-tool-ids
      (clojure.set/difference all-tool-ids public-tool-ids))
    all-tool-ids))

(defn- admin-filter-listing-tool-ids
  [public-tool-ids params]
  (when (contains? params :public)
    (filter-listing-tool-ids (set (persistence/get-tool-ids)) public-tool-ids params)))

(defn list-tools
  "Obtains a listing of tools accessible to the given user."
  [{:keys [user] :as params}]
  (let [public-tool-ids (perms-client/get-public-tool-ids)
        perms           (perms-client/load-tool-permissions user)
        tool-ids        (filter-listing-tool-ids (set (keys perms)) public-tool-ids params)
        paged-params    (assoc params :tool-ids tool-ids :deprecated false)
        unpaged-params  (dissoc paged-params :limit :offset)
        total           (count (persistence/get-tool-listing unpaged-params))]
    {:total total
     :tools
     (map (partial format-tool-listing perms public-tool-ids)
          (persistence/get-tool-listing paged-params))}))

(defn get-tool
  "Obtains a tool by ID."
  [user tool-id]
  (let [tool           (->> (persistence/get-tool tool-id)
                            (format-tool-listing (perms-client/load-tool-permissions user)
                                                 (perms-client/get-public-tool-ids)))
        container      (clojure.set/rename-keys (tool-container-info tool-id) {:ports :container_ports})
        implementation (persistence/get-tool-implementation-details tool-id)]
    (assoc tool
         :container container
         :implementation implementation)))

(defn user-get-tool
  "Obtains tool details for a user."
  [user tool-id]
  (permissions/check-tool-permissions user "read" [tool-id])
  (get-tool user tool-id))

(defn admin-list-tools
  "Obtains a listing of any tool for admin users."
  [{:keys [user] :as params}]
  (let [public-tool-ids (perms-client/get-public-tool-ids)
        perms           (perms-client/load-tool-permissions user)
        params          (-> params
                            (assoc :tool-ids (admin-filter-listing-tool-ids public-tool-ids params))
                            remove-nil-vals)]
    {:tools
     (map (partial format-tool-listing perms public-tool-ids)
          (persistence/get-tool-listing params))}))

(defn- add-new-tool
  [{:keys [container] :as tool}]
  (verify-tool-name-version tool)
  (let [tool-id (persistence/add-tool tool)]
    (when container
      (add-tool-container tool-id container))
    tool-id))

(defn admin-add-tools
  "Adds a list of tools to the database, returning a list of IDs of the tools added."
  [{:keys [tools]}]
  (transaction
    (let [tool-ids (doall (map add-new-tool tools))]
      (dorun (map perms-client/register-public-tool tool-ids))
      {:tool_ids tool-ids})))

(defn verify-tool-name-version-for-update
  "Given the current tool and the tool values for update,
   verifies the name and version values for update do not already exist for another tool."
  [{current-name :name current-version :version} {:keys [name version]}]
  (when (or (and name (not= current-name name))
            (and version (not= current-version version)))
    (verify-tool-name-version {:name    (or name current-name)
                               :version (or version current-version)})))

(defn admin-update-tool
  [user overwrite-public {:keys [id container] :as tool}]
  (transaction
    (verify-tool-name-version-for-update (persistence/get-tool id) tool)
    (persistence/update-tool tool)
    (when container
      (set-tool-container id overwrite-public container))
    (get-tool user id)))

(defn delete-tool
  [tool-id]
  (persistence/delete-tool tool-id)
  (try+
    (perms-client/delete-tool-resource tool-id)
    (catch [:status 404] _
      (log/warn "tool resource" tool-id "not found by permissions service")))
  nil)

(defn admin-delete-tool
  "Deletes a tool, as long as it is not in use by any apps."
  [user tool-id]
  (let [{:keys [name location version]} (get-tool user tool-id)]
    (validate-tool-not-used tool-id)
    (log/warn user "deleting tool" tool-id name version "@" location))
  (delete-tool tool-id))

(defn admin-publish-tool
  [{user :shortUsername :as current-user} {:keys [id] :as tool}]
  (admin-update-tool user false tool)
  (perms-client/make-tool-public id)
  (tool-requests/complete-tool-request id (config/uid-domain) current-user)
  (get-tool user id))

(defn- validate-tool-for-tool-request
  "Ensures the given tool ID exists, the user has 'own' permission for that tool, the tool is not deprecated,
   and a tool request has not already been submitted for the tool."
  [user tool-id]
  (let [image (-> (get-tool user tool-id) :container :image)]
    (permissions/check-tool-permissions user "own" [tool-id])
    (when (:deprecated image)
      (throw+ {:type  :clojure-commons.exception/bad-request-field
               :error "Tool is using a deprecated image and can not be made public."
               :image image})))
  (if-let [request-id (tool-req-db/get-request-id-for-tool tool-id)]
    (exception-util/exists "A tool request has already been submitted for the given tool"
                           :tool_id tool-id
                           :tool_request_id request-id)))

(defn submit-tool-request
  "Validates the tool with the given tool_id in the request, if any, before submitting the new tool request."
  [{:keys [shortUsername] :as user} {:keys [tool_id] :as request}]
  (when tool_id (validate-tool-for-tool-request shortUsername tool_id))
  (tool-requests/submit-tool-request user request))
