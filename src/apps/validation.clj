(ns apps.validation
  (:require
   [apps.clients.permissions :as perms-client]
   [apps.persistence.app-metadata :as persistence]
   [apps.persistence.entities :refer [tools]]
   [apps.persistence.users :refer [get-existing-user-id]]
   [clojure-commons.exception-util :as exception-util]
   [clojure.string :refer [blank?]]
   [korma.core :refer [fields select select* where]]
   [slingshot.slingshot :refer [throw+]]))

(defn- brief-tool-details-base-query
  []
  (-> (select* tools)
      (fields :id :name :version)))

(defn verify-tool-name-version
  [tool]
  (when-let [existing-tool (-> (brief-tool-details-base-query)
                               (where (select-keys tool [:name :version]))
                               select
                               first)]
    (exception-util/exists "A Tool with that name and version already exists." :tool existing-tool)))

(defn validate-image-not-used-in-public-apps
  [image-id]
  (let [tools (persistence/get-tools-in-public-apps-by-image-id image-id)]
    (when-not (empty? tools)
      (throw+ {:type  :clojure-commons.exception/not-writeable
               :error "Image already used by tools in public apps."
               :tools tools}))))

(defn validate-image-not-used
  [image-id]
  (let [tools (-> (brief-tool-details-base-query)
                  (where (or {:container_images_id image-id}
                             {:id [:in (persistence/subselect-tool-ids-using-data-container image-id)]}))
                  select)]
    (when-not (empty? tools)
      (throw+ {:type  :clojure-commons.exception/not-writeable
               :error "Image already used by tools."
               :tools tools}))))

(defn validate-tool-not-public
  [tool-id]
  (let [public-tool-ids (perms-client/get-public-tool-ids)]
    (when (contains? public-tool-ids tool-id)
      (throw+ {:type    :clojure-commons.exception/not-writeable
               :error   "This tool is already public."
               :tool_id tool-id}))))

(defn validate-tool-not-used-in-public-apps
  [tool-id]
  (let [apps (persistence/get-public-apps-by-tool-id tool-id)]
    (when-not (empty? apps)
      (throw+ {:type  :clojure-commons.exception/not-writeable
               :error "This tool is already in use by public apps."
               :apps  apps}))))

(defn validate-tool-not-used
  [tool-id]
  (let [app-ids (persistence/get-app-ids-by-tool-id tool-id)]
    (when-not (empty? app-ids)
      (throw+ {:type  :clojure-commons.exception/not-writeable
               :error "This tool is already in use by apps."
               :apps  (map persistence/get-app app-ids)}))))

(defn validate-external-app-step
  "Verifies that an external app step in a pipeline has all of the required fields."
  [step-number {external-app-id :external_app_id}]
  (when (blank? external-app-id)
    (throw+ {:type  :clojure-commons.exception/missing-request-field
             :error (str "pipeline step " step-number " contians neither a task ID nor an "
                         "external app ID")})))

(defn validate-parameter
  "Ensures that hidden output parameters have a filename defined."
  [{default-value :defaultValue
    param-type :type
    {implicit :is_implicit} :file_parameters
    visible :isVisible
    :or {visible true}
    :as parameter}]
  (when (and (contains? persistence/param-output-types param-type)
             (blank? default-value)
             (or (not visible) implicit))
    (throw+ {:type      :clojure-commons.exception/missing-request-field
             :error     "Hidden output parameters must define a default value."
             :parameter parameter})))

(defn validate-pipeline
  "Verifies that a pipeline contains at least 2 steps and at least 1 input->ouput mapping."
  [{:keys [steps mappings]}]
  (when (< (count steps) 2)
    (throw+ {:type  :clojure-commons.exception/missing-request-field
             :error "Cannot save a workflow with less than 2 steps defined."
             :steps steps}))
  (when (< (count mappings) 1)
    (throw+ {:type     :clojure-commons.exception/missing-request-field
             :error    "Cannot save a workflow without input->output mappings defined."
             :mappings mappings})))

(defn get-valid-user-id
  "Gets the user ID for the given username, or throws an error if that username is not found."
  [username]
  (let [user-id (get-existing-user-id username)]
    (when (nil? user-id)
      (throw+ {:type  :clojure-commons.exception/bad-request-field
               :error (str "No user found for username " username)}))
    user-id))
