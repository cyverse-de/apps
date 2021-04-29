(ns apps.service.apps.de.admin
  (:use [apps.persistence.app-metadata.relabel :only [update-app-labels]]
        [apps.util.assertions :only [assert-not-nil]]
        [apps.util.config :only [workspace-public-id]]
        [apps.util.db :only [transaction]]
        [slingshot.slingshot :only [throw+]])
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [apps.clients.email :as email]
            [apps.clients.metadata :as metadata-client]
            [apps.persistence.app-groups :as app-groups]
            [apps.persistence.app-metadata :as persistence]
            [apps.persistence.categories :as db-categories]
            [apps.service.apps.de.categorization :as categorization]
            [apps.service.apps.de.constants :as c]
            [apps.service.apps.de.validation :as av]
            [clojure-commons.exception-util :as ex-util]))

(def ^:private max-app-category-name-len 255)

(defn- validate-app-category-existence
  "Retrieves all app category fields from the database."
  [category-id]
  (assert-not-nil [:category_id category-id] (app-groups/get-app-category category-id)))

(defn- validate-app-category-name
  "Validates the length of an App Category name."
  [name]
  (when (> (count name) max-app-category-name-len)
    (ex-util/illegal-argument "App Category name too long."
                              :name  name)))

(defn- validate-subcategory-name
  "Validates that the given subcategory name is available under the given App Category parent ID."
  [parent-id name]
  (when (app-groups/category-contains-subcategory? parent-id name)
    (ex-util/illegal-argument "Parent App Category already contains a subcategory with that name"
                              :parent_id parent-id
                              :name      name)))

(defn- validate-category-empty
  "Validates that the given App Category contains no Apps directly under it."
  [parent-id]
  (when (app-groups/category-contains-apps? parent-id)
    (ex-util/illegal-argument "Parent App Category already contains Apps"
                              :parent_id parent-id)))

(defn- validate-category-hierarchy-empty
  "Validates that the given App Category and its subcategories contain no Apps."
  [category-id requestor]
  (when (app-groups/category-hierarchy-contains-apps? category-id)
    (ex-util/illegal-argument "App Category, or one of its subcategories, still contain Apps"
                              :category_id  category-id
                              :requested_by requestor)))

(defn- validate-category-not-ancestor-of-parent
  [category-id parent-id]
  (when (app-groups/category-ancestor-of-subcategory? category-id parent-id)
    (ex-util/illegal-argument "App Category is an ancestor of the destination Category"
                              :category_id category-id
                              :parent_id   parent-id)))

(defn- app-deletion-notify
  [{app-id :id app-name :name}]
  (let [{:keys [integrator_name integrator_email]} (persistence/get-integration-data-by-app-id app-id)]
    (when integrator_email
      (email/send-app-deletion-notification integrator_name integrator_email app-name c/system-id app-id))))

(defn delete-app
  "This service marks an existing app as deleted in the database."
  [app-id]
  (let [app (persistence/get-app app-id)]
    (persistence/delete-app true app-id)
    (app-deletion-notify app))
  nil)

(defn- update-app-deleted-disabled
  "Updates only an App's deleted or disabled flags in the database."
  [{app-id :id :keys [deleted disabled]}]
  (when-not (nil? deleted)
    (persistence/delete-app deleted app-id))
  (when-not (nil? disabled)
    (persistence/disable-app disabled app-id)))

(defn- update-app-extra-info
  "Updates any extra information specific to execution platforms or similar."
  [{app-id :id :keys [extra]}]
  (when-let [htcondor (:htcondor extra)]
    (when-let [extra-requirements (:extra_requirements htcondor)]
      (persistence/set-htcondor-extra app-id extra-requirements))))

(defn- update-app-details
  "Updates high-level details and labels in an App, including deleted and disabled flags in the
   database."
  [{app-id :id :keys [references groups extra] :as app}]
  (persistence/update-app app)
  (when-not (empty? references)
    (persistence/set-app-references app-id references))
  (when-not (empty? groups)
    (update-app-labels (select-keys app [:id :groups])))
  (when-not (empty? extra)
    (update-app-extra-info app)))

(defn update-app
  "This service updates high-level details and labels in an App, extra information for particular
   job execution systems, and can mark or unmark the app as deleted or disabled in the database."
  [{username :shortUsername} {app-name :name app-id :id :as app}]
  (transaction
   (av/validate-app-existence app-id)
   (when-not (nil? app-name)
     (categorization/validate-app-name-in-current-hierarchy username app-id app-name)
     (av/validate-app-name app-name app-id))
   (if (empty? (select-keys app [:name :description :wiki_url :references :groups :extra]))
     (update-app-deleted-disabled app)
     (update-app-details app))))

(defn bless-app
  "This service marks an app as having been reviewed and certified by Discovery Environment
   administrators."
  [{username :shortUsername} app-id]
  (transaction
   (av/validate-app-existence app-id)
   (metadata-client/update-avus username app-id (json/encode {:avus [c/certified-avu]}))))

(defn remove-app-blessing
  "This service marks an app as _not_ having been reviewed and certified by Discovery Environment
   administrators."
  [{username :shortUsername} app-id]
  (transaction
   (av/validate-app-existence app-id)
   (metadata-client/delete-avus username [app-id] [c/certified-avu])))

(defn add-category
  "Adds an App Category to a parent Category, as long as that parent does not contain any Apps."
  [{:keys [name parent_id] :as category}]
  (validate-app-category-existence parent_id)
  (validate-app-category-name name)
  (validate-subcategory-name parent_id name)
  (validate-category-empty parent_id)
  (transaction
   (let [category-id (:id (app-groups/create-app-group (workspace-public-id) category))]
     (app-groups/add-subgroup parent_id category-id)
     category-id)))

(defn- delete-category*
  "Deletes a category."
  [{:keys [username]} {:keys [id name]}]
  (log/warnf "%s deleting category \"%s\" (%s) and all of its subcategories" username name id)
  (app-groups/delete-app-category id))

(defn delete-category
  "Deletes a single app category."
  [user category-id]
  (let [category (validate-app-category-existence category-id)]
    (validate-category-hierarchy-empty category-id (:username user))
    (delete-category* user category)
    nil))

(defn update-category
  "Updates an App Category's name or parent Category."
  [{category-id :id :keys [name parent_id] :as category}]
  (transaction
   (let [category (validate-app-category-existence category-id)]
     (when name
       (validate-app-category-name name)
       (app-groups/update-app-category category-id name))
     (when parent_id
       (validate-subcategory-name parent_id (or name (:name category)))
       (validate-category-empty parent_id)
       (app-groups/decategorize-category category-id)
       (validate-category-not-ancestor-of-parent category-id parent_id)
       (app-groups/add-subgroup parent_id category-id)))))

(defn list-ontologies
  [{:keys [username]}]
  (let [active-version              (db-categories/get-active-hierarchy-version)
        {ontology-list :ontologies} (metadata-client/list-ontologies username)]
    {:ontologies (map #(assoc % :active (= active-version (:version %))) ontology-list)}))

(defn set-category-ontology-version
  "Sets the active ontology-version for use in apps hierarchy endpoints."
  [{:keys [username]} ontology-version]
  (let [version-details (db-categories/add-hierarchy-version username ontology-version)]
    (assoc version-details :applied_by username)))

(defn delete-ontology
  [{:keys [username]} ontology-version]
  (let [active-version (db-categories/get-active-hierarchy-version)]
    (when (= ontology-version active-version)
      (ex-util/illegal-argument "The active app hierarchy version cannot be marked as deleted.")))
  (metadata-client/delete-ontology username ontology-version))
