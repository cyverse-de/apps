(ns apps.persistence.app-metadata
  "Persistence layer for app metadata."
  (:use [apps.constants :only [de-system-id]]
        [apps.persistence.entities]
        [apps.persistence.users :only [get-user-id]]
        [apps.user :only [current-user]]
        [apps.util.assertions]
        [apps.util.conversions :only [remove-nil-vals]]
        [apps.util.db :only [transaction]]
        [kameleon.queries :only [add-query-sorting add-query-offset add-query-limit conditional-where]]
        [kameleon.util :only [normalize-string]]
        [kameleon.util.search :only [format-query-wildcards]]
        [kameleon.uuids :only [uuidify]]
        [korma.core :exclude [update]])
  (:require [apps.clients.permissions :as perms-client]
            [apps.persistence.app-listing :as app-listing]
            [apps.persistence.app-metadata.delete :as delete]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure-commons.exception-util :as cxu]
            [korma.core :as sql])
  (:import [java.util UUID]))

(def param-multi-input-type "MultiFileSelector")
(def param-flex-input-type "FileFolderInput")
(def param-input-reference-types #{"ReferenceAnnotation" "ReferenceGenome" "ReferenceSequence"})
(def param-ds-input-types #{"FileInput" "FolderInput" param-multi-input-type param-flex-input-type})
(def param-input-types (set/union param-ds-input-types param-input-reference-types))
(def param-output-types #{"FileOutput" "FolderOutput" "MultiFileOutput"})
(def param-file-types (set/union param-input-types param-output-types))

(def param-selection-types #{"TextSelection" "DoubleSelection" "IntegerSelection"})
(def param-tree-type "TreeSelection")
(def param-list-types (conj param-selection-types param-tree-type))

(def param-reference-genome-types #{"ReferenceGenome" "ReferenceSequence" "ReferenceAnnotation"})

(defn- filter-valid-task-values
  "Filters valid keys from the given Task for inserting or updating in the database."
  [task]
  (select-keys task [:name :description :label :tool_id :external_app_id]))

(defn- filter-valid-app-group-values
  "Filters and renames valid keys from the given App group for inserting or updating in the database."
  [group]
  (-> group
      (set/rename-keys {:isVisible :is_visible})
      (select-keys [:id :task_id :name :description :label :display_order :is_visible])))

(defn- filter-valid-app-parameter-values
  "Filters and renames valid keys from the given App parameter for inserting or updating in the
  database."
  [parameter]
  (-> parameter
      (set/rename-keys {:isVisible :is_visible
                        :order :ordering})
      (select-keys [:id
                    :parameter_group_id
                    :name
                    :description
                    :label
                    :is_visible
                    :ordering
                    :display_order
                    :parameter_type
                    :required
                    :omit_if_blank])))

(defn- filter-valid-file-parameter-values
  "Filters valid keys from the given file-parameter for inserting or updating in the database."
  [file-parameter]
  (select-keys file-parameter [:parameter_id
                               :retain
                               :is_implicit
                               :info_type
                               :data_format
                               :data_source_id
                               :repeat_option_flag]))

(defn- filter-valid-parameter-value-values
  "Filters and renames valid keys from the given parameter-value for inserting or updating in the
  database."
  [parameter-value]
  (-> parameter-value
      (set/rename-keys {:display :label
                        :isDefault :is_default})
      (select-keys [:id
                    :parameter_id
                    :parent_id
                    :is_default
                    :display_order
                    :name
                    :value
                    :description
                    :label])))

(defn app-exists?
  "Determines whether or not an app exists."
  [app-id]
  (not (nil? (app-listing/get-app-listing (uuidify app-id)))))

(defn get-app
  "Retrieves all app listing fields from the database."
  [app-id]
  (assert-not-nil [:app-id app-id] (app-listing/get-app-listing (uuidify app-id))))

(defn get-app-latest-version
  "Retrieves the latest version field from the app listing view in the database."
  [app-id]
  (-> app-id
      get-app
      :version_id))

(defn- user-id-subselect [username]
  (subselect :users
             (fields :id)
             (where {:username username})))

(defn get-integration-data-by-username [username]
  (first (select integration_data (where {:user_id (user-id-subselect username)}))))

(defn get-integration-data-by-email [integrator-email]
  (first (select integration_data (where {:integrator_email integrator-email}))))

(defn- add-integration-data [username integrator-email integrator-name]
  (when username (get-user-id username))
  (insert integration_data (values {:integrator_email integrator-email
                                    :integrator_name  integrator-name
                                    :user_id          (user-id-subselect username)})))

(defn- lookup-integration-data [username integrator-email integrator-name]
  (or (get-integration-data-by-username username)
      (get-integration-data-by-email integrator-email)
      (add-integration-data username integrator-email integrator-name)))

(defn- can-update-integration-data? [id integrator-email integrator-name]
  (zero? ((comp :count first)
          (select :integration_data
                  (aggregate (count :id) :count)
                  (where {:id               [not= id]
                          :integrator_email integrator-email
                          :integrator_name  integrator-name})))))

(defn- auto-update-integration-data [{:keys [id]} username integrator-email integrator-name]
  (when (can-update-integration-data? id integrator-email integrator-name)
    (sql/update integration_data
                (set-fields {:integrator_email integrator-email
                             :integrator_name  integrator-name
                             :user_id          (user-id-subselect username)})
                (where {:id id})))
  (first (select integration_data (where {:id id}))))

(defn get-integration-data
  "Retrieves integrator info from the database, adding it first if not already there."
  ([{:keys [username email first-name last-name]}]
   (get-integration-data username email (str first-name " " last-name)))
  ([integrator-email integrator-name]
   (get-integration-data nil integrator-email integrator-name))
  ([username integrator-email integrator-name]
   (let [integration-data (lookup-integration-data username integrator-email integrator-name)]
     (if (or (not= (:integrator_email integration-data) integrator-email)
             (not= (:integrator_name integration-data) integrator-name)
             (nil? (:user_id integration-data)))
       (auto-update-integration-data integration-data username integrator-email integrator-name)
       integration-data))))

(defn update-integration-data
  "Updates an integration data record."
  [id name email]
  (sql/update integration_data
              (set-fields {:integrator_email email
                           :integrator_name  name})
              (where {:id id})))

(defn- add-integration-data-search-clause [query search]
  (if-not (nil? search)
    (let [search (str "%" (format-query-wildcards search) "%")]
      (where query
             (or {(sqlfn lower :integrator_name) [like (sqlfn lower search)]}
                 {(sqlfn lower :integrator_email) [like (sqlfn lower search)]})))
    query))

(defn- integration-data-base-query []
  (-> (select* [:integration_data :d])
      (join [:users :u] {:d.user_id :u.id})
      (fields :d.id :d.integrator_name :d.integrator_email :u.username)))

(defn get-integration-data-by-id [integration-data-id]
  (-> (integration-data-base-query)
      (where {:d.id integration-data-id})
      select
      first))

(defn get-integration-data-by-tool-id [tool-id]
  (-> (integration-data-base-query)
      (join [:tools :t] {:t.integration_data_id :d.id})
      (where {:t.id tool-id})
      select
      first))

(defn get-integration-data-by-app-version-id [app-version-id]
  (-> (integration-data-base-query)
      (join [:app_versions :a] {:a.integration_data_id :d.id})
      (where {:a.id app-version-id})
      select
      first))

(defn get-integration-data-by-app-id [app-id]
  (-> app-id
      get-app-latest-version
      get-integration-data-by-app-version-id))

(defn update-app-integration-data [app-id integration-data-id]
  (-> (update* :apps)
      (set-fields {:integration_data_id integration-data-id})
      (where {:id app-id})
      (sql/update)))

(defn update-tool-integration-data [tool-id integration-data-id]
  (-> (update* :tools)
      (set-fields {:integration_data_id integration-data-id})
      (where {:id tool-id})
      (sql/update)))

(defn list-integration-data [search limit offset sort-field sort-dir]
  (-> (select* [:integration_data :d])
      (join [:users :u] {:d.user_id :u.id})
      (fields :d.id :d.integrator_name :d.integrator_email :u.username)
      (add-integration-data-search-clause search)
      (add-query-sorting sort-field sort-dir)
      (add-query-offset offset)
      (add-query-limit limit)
      select))

(defn count-integration-data [search]
  (-> (select* :integration_data)
      (aggregate (count :*) :count)
      (add-integration-data-search-clause search)
      select
      first
      :count))

(defn get-tool-ids-by-integration-data-id [integration-data-id]
  (mapv :id (-> (select* :tools)
                (fields :id)
                (where {:integration_data_id integration-data-id})
                select)))

(defn get-app-ids-by-integration-data-id [integration-data-id]
  (mapv :id (-> (select* :apps)
                (fields :id)
                (where {:integration_data_id integration-data-id})
                select)))

(defn delete-integration-data [integration-data-id]
  (-> (delete* :integration_data)
      (where {:id integration-data-id})
      delete))

(defn- get-tool-listing-base-query
  "Common select query for tool listings."
  []
  (-> (select* tool_listing)
      (fields [:tool_id :id]
              :name
              :description
              :location
              :type
              :version
              :attribution)))

(defn get-app-tools
  "Loads information about the tools associated with an app."
  ([app-id]
   (get-app-tools app-id (get-app-latest-version app-id)))
  ([app-id version-id]
  (select (get-tool-listing-base-query)
          (join :container_images {:tool_listing.container_images_id :container_images.id})
          (fields [:container_images.name       :image_name]
                  [:container_images.tag        :image_tag]
                  [:container_images.url        :image_url]
                  [:container_images.deprecated :deprecated])
          (where {:app_id         app-id
                  :app_version_id version-id}))))

(defn get-app-notification-types
  "Loads information about the notification types to use for an app."
  [app-id]
  (->> (select [:app_steps :step]
               (join [:tasks :task] {:step.task_id :task.id})
               (join [:tools :tool] {:task.tool_id :tool.id})
               (join [:tool_types :tt] {:tool.tool_type_id :tt.id})
               (fields :tt.notification_type)
               (where {:step.app_id (uuidify app-id)}))
       (map :notification_type)))

(defn subselect-tool-ids-using-data-container
  "Query subselect for tool IDs of tools using a Docker image as a data container."
  [data-container-image-id]
  (subselect tools
             (fields :id)
             (join [:container_settings :settings]
                   {:settings.tools_id :tools.id})
             (join [:container_volumes_from :vf]
                   {:vf.container_settings_id :settings.id})
             (join [:data_containers :data]
                   {:data.id :vf.data_containers_id})
             (where {:data.container_images_id data-container-image-id})))

(defn get-tools-in-public-apps-by-image-id
  "Loads information about tools used by public apps associated with a Docker image."
  [img-id]
  (select (get-tool-listing-base-query)
          (modifier "DISTINCT")
          (where {:app_id [in (perms-client/get-public-app-ids)]})
          (where (or {:container_images_id img-id}
                     {:tool_id [in (subselect-tool-ids-using-data-container img-id)]}))))

(defn get-app-ids-by-tool-id
  "Gets a list of IDs of the apps using the tool with the given ID."
  [tool-id]
  (let [tool-app-ids (select tool_listing (fields :app_id) (where {:tool_id tool-id}))]
    (map :app_id tool-app-ids)))

(defn get-public-apps-by-tool-id
  "Loads information about the public apps using the tool with the given ID."
  [tool-id]
  (->> (select tool_listing
               (fields :app_id)
               (where {:tool_id tool-id
                       :app_id  [in (perms-client/get-public-app-ids)]}))
       (map (comp get-app :app_id))))

(defn parameter-types-for-tool-type
  "Lists the valid parameter types for the tool type with the given identifier."
  ([tool-type-id]
   (parameter-types-for-tool-type (select* parameter_types) tool-type-id))
  ([base-query tool-type-id]
   (select base-query
           (join :tool_type_parameter_type
                 {:tool_type_parameter_type.parameter_type_id
                  :parameter_types.id})
           (where {:tool_type_parameter_type.tool_type_id tool-type-id}))))

(defn add-app
  "Adds top-level app info to the database and returns the new app info, including its new ID."
  [app]
  (as-> (select-keys app [:id :name :description]) app-info
        (insert apps (values app-info))))

(defn add-app-version
  "Adds top-level app version info to the database and returns the new info, including its new ID."
  ([app-version]
   (add-app-version app-version current-user))
  ([{:keys [version] :as app-version :or {version "Unversioned"}} user]
   (as-> (select-keys app-version [:id :app_id :version_order]) version-info
         (assoc version-info :version version
                             :integration_data_id (:id (get-integration-data user))
                             :edited_date         (sqlfn now))
         (insert app_versions (values version-info)))))

(defn add-step
  "Adds an app step to the database for the given app ID."
  [version-id step-number step]
  (let [step (-> step
                 (select-keys [:task_id])
                 (update-in [:task_id] uuidify)
                 (assoc :app_version_id version-id
                        :step step-number))]
    (insert app_steps (values step))))

(defn- add-workflow-io-map
  [mapping]
  (insert :workflow_io_maps
          (values {:app_version_id (:app_version_id mapping)
                   :source_step    (get-in mapping [:source_step :id])
                   :target_step    (get-in mapping [:target_step :id])})))

(defn- build-io-key
  [step de-app-key external-app-key value]
  (let [stringify #(if (keyword? %) (name %) %)]
    (if (= (:app_type step) "External")
      [external-app-key (stringify value)]
      [de-app-key       (uuidify value)])))

(defn- io-mapping-builder
  [mapping-id mapping]
  (fn [[input output]]
    (into {} [(vector :mapping_id mapping-id)
              (build-io-key (:target_step mapping) :input :external_input input)
              (build-io-key (:source_step mapping) :output :external_output output)])))

(defn add-mapping
  "Adds an input/output workflow mapping to the database for the given app source->target mapping."
  [mapping]
  (let [mapping-id (:id (add-workflow-io-map mapping))]
    (->> (:map mapping)
         (map (io-mapping-builder mapping-id mapping))
         (map #(insert :input_output_mapping (values %)))
         (dorun))))

(defn update-app
  "Updates top-level app info in the database."
  ([app]
   (update-app app false))
  ([app publish?]
   (let [app-id (:id app)
         app (-> app
                 (select-keys [:name :description :wiki_url])
                 (remove-nil-vals))]
     (sql/update apps (set-fields app) (where {:id app-id})))))

(defn update-app-version
  "Updates top-level app version info in the database."
  ([version-info]
   (update-app-version version-info false))
  ([version-info publish?]
   (let [version-id   (:version_id version-info)
         version-info (-> version-info
                          (select-keys [:version :deleted :disabled])
                          (assoc :edited_date (sqlfn now)
                                 :integration_date (when publish? (sqlfn now)))
                          (remove-nil-vals))]
     (sql/update app_versions (set-fields version-info) (where {:id version-id})))))

(defn- get-app-publication-status-code-id
  [status-code]
  (-> (select* :app_publication_request_status_codes)
      (fields :id)
      (where {:name status-code})
      select
      first
      :id))

(defn create-publication-request
  "Creates an app publication request."
  [username app-id]
  (let [user-id        (get-user-id username)
        request-id     (UUID/randomUUID)
        status-code-id (get-app-publication-status-code-id "Submitted")]

    (insert :app_publication_requests
            (values {:id           request-id
                     :requestor_id user-id
                     :app_id       app-id}))

    (insert :app_publication_request_statuses
            (values {:app_publication_request_id             request-id
                     :app_publication_request_status_code_id status-code-id
                     :updater_id                             user-id}))

    request-id))

(defn- add-app-reference
  "Adds an App's reference to the database."
  [version-id reference]
  (insert app_references (values {:app_version_id version-id, :reference_text reference})))

(defn set-app-references
  "Resets the given App's references with the given list."
  [version-id references]
  (transaction
   (delete app_references (where {:app_version_id version-id}))
   (dorun (map (partial add-app-reference version-id) references))))

(defn set-htcondor-extra
  [version-id extra-requirements]
  (transaction
   (delete apps_htcondor_extra (where {:app_version_id version-id}))
   (if-not (or (nil? extra-requirements) (empty? (string/trim extra-requirements)))
     (insert apps_htcondor_extra (values {:app_version_id version-id, :extra_requirements extra-requirements})))))

(defn- get-job-type-id-for-system* [system-id]
  (:id (first (select :job_types (fields :id) (where {:system_id system-id})))))

(defn- get-job-type-id-for-system [system-id]
  (let [job-type-id (get-job-type-id-for-system* system-id)]
    (when (nil? job-type-id)
      (cxu/bad-request (str "unrecognized system ID: " system-id)))
    job-type-id))

(defn- tool-type-for [tool-id]
  (-> (select* [:tools :t])
      (join [:tool_types :tt] {:t.tool_type_id :tt.id})
      (fields [:tt.name :tool_type])
      (where {:t.id tool-id})
      select
      first
      :tool_type))

;; FIXME: this association between tool types and job types is kind of flimsy. We can't do it now,
;; but it would be a good idea to rethink tool types and job types.
(defn- get-job-type-id-for-tool
  "Determines the job type to use for a specific tool ID. Job types and tool types are associated by
   system name. If the tool type name happens to be the name of an existing system ID then the job
   type associated with that system ID is used. Otherwise, the default job type (the one associated
   with the `de` system) is used."
  [tool-id]
  (let [tool-type (assert-not-nil [:tool-id tool-id] (tool-type-for tool-id))]
    (or (get-job-type-id-for-system* tool-type)
        (get-job-type-id-for-system de-system-id))))

(defn- get-job-type-id-for-task [system-id {tool-id :tool_id}]
  (if tool-id
    (get-job-type-id-for-tool tool-id)
    (get-job-type-id-for-system system-id)))

(defn add-task
  "Adds a task to the database."
  ([task]
   (add-task (:system_id task) task))
  ([system-id task]
   (let [job-type-id (get-job-type-id-for-task system-id task)]
     (insert tasks (values (assoc (filter-valid-task-values task) :job_type_id job-type-id))))))

(defn update-task
  "Updates a task in the database."
  [system-id {task-id :id :as task}]
  (let [job-type-id (get-job-type-id-for-task system-id task)]
    (sql/update tasks
                (set-fields (assoc (filter-valid-task-values task) :job_type_id job-type-id))
                (where {:id task-id}))))

(defn remove-tool-from-tasks
  "Removes the given tool ID from all tasks."
  [tool-id]
  (sql/update tasks
              (set-fields {:tool_id nil})
              (where      {:tool_id tool-id})))

(defn remove-app-steps
  "Removes all steps from an App. This delete will cascade to workflow_io_maps and
  input_output_mapping entries."
  [app-version-id]
  (delete app_steps (where {:app_version_id app-version-id})))

(defn remove-workflow-map-orphans
  "Removes any orphaned workflow_io_maps table entries."
  []
  (delete :workflow_io_maps
          (where (not (exists (subselect [:input_output_mapping :iom]
                                         (where {:iom.mapping_id :workflow_io_maps.id})))))))

(defn remove-parameter-mappings
  "Removes all input-output mappings associated with the given parameter ID, then removes any
  orphaned workflow_io_maps table entries."
  [parameter-id]
  (transaction
   (delete :input_output_mapping (where (or {:input parameter-id}
                                            {:output parameter-id})))
   (remove-workflow-map-orphans)))

(defn get-app-group
  "Fetches an App group."
  ([group-id]
   (first (select parameter_groups (where {:id group-id}))))
  ([group-id task-id]
   (first (select parameter_groups (where {:id group-id, :task_id task-id})))))

(defn add-app-group
  "Adds an App group to the database."
  [group]
  (insert parameter_groups (values (filter-valid-app-group-values group))))

(defn update-app-group
  "Updates an App group in the database."
  [{group-id :id :as group}]
  (sql/update parameter_groups
              (set-fields (filter-valid-app-group-values group))
              (where {:id group-id})))

(defn remove-app-group-orphans
  "Removes groups associated with the given task ID, but not in the given group-ids list."
  [task-id group-ids]
  (delete parameter_groups (where {:task_id task-id
                                   :id [not-in group-ids]})))

(defn get-parameter-type-id
  "Gets the ID of the given parameter type name."
  [parameter-type]
  (:id (first
        (select parameter_types
                (fields :id)
                (where {:name parameter-type :deprecated false})))))

(defn get-info-type-id
  "Gets the ID of the given info type name."
  [info-type]
  (:id (first
        (select info_type
                (fields :id)
                (where {:name info-type :deprecated false})))))

(defn get-data-format-id
  "Gets the ID of the data format with the given name."
  [data-format]
  (:id (first
        (select data_formats
                (fields :id)
                (where {:name data-format})))))

(defn get-data-source-id
  "Gets the ID of the data source with the given name."
  [data-source]
  (:id (first
        (select data_source
                (fields :id)
                (where {:name data-source})))))

(defn get-app-parameter
  "Fetches an App parameter."
  ([parameter-id]
   (first (select parameters (where {:id parameter-id}))))
  ([parameter-id task-id]
   (first (select :task_param_listing (where {:id parameter-id, :task_id task-id})))))

(defn add-app-parameter
  "Adds an App parameter to the parameters table."
  [{param-type :type :as parameter}]
  (insert parameters
          (values (filter-valid-app-parameter-values
                   (assoc parameter :parameter_type (get-parameter-type-id param-type))))))

(defn update-app-parameter
  "Updates a parameter in the parameters table."
  [{parameter-id :id param-type :type :as parameter}]
  (sql/update parameters
              (set-fields (filter-valid-app-parameter-values
                           (assoc parameter
                                  :parameter_type (get-parameter-type-id param-type))))
              (where {:id parameter-id})))

(defn remove-parameter-orphans
  "Removes parameters associated with the given group ID, but not in the given parameter-ids list."
  [group-id parameter-ids]
  (delete parameters (where {:parameter_group_id group-id
                             :id [not-in parameter-ids]})))

(defn clear-group-parameters
  "Removes parameters associated with the given group ID."
  [group-id]
  (delete parameters (where {:parameter_group_id group-id})))

(defn add-file-parameter
  "Adds file parameter fields to the database."
  [{info-type :file_info_type data-format :format data-source :data_source :as file-parameter}]
  (insert file_parameters
          (values (filter-valid-file-parameter-values
                   (assoc file-parameter
                          :info_type (get-info-type-id info-type)
                          :data_format (get-data-format-id data-format)
                          :data_source_id (get-data-source-id data-source))))))

(defn remove-file-parameter
  "Removes all file parameters associated with the given parameter ID."
  [parameter-id]
  (delete file_parameters (where {:parameter_id parameter-id})))

(defn get-rule-type
  "Retrieves information about a validation rule from the database."
  [rule-type-name]
  (-> (select* :rule_type)
      (fields :id :name :description :label :deprecated)
      (where {:name rule-type-name})
      select
      first))

(defn get-rule-arguments
  "Retrieves validation rule arguments corresponding to a rule ID from the database."
  [rule-id]
  (-> (select* :validation_rule_arguments)
      (fields :argument_value)
      (where {:rule_id rule-id})
      (order :ordering)
      select))

(defn get-rule-arg-definitions
  "Retrieves validation rule argument definitions corresponding to a rule type ID from the database."
  [rule-type-id]
  (-> (select* [:validation_rule_argument_definitions :vrad])
      (join [:validation_rule_argument_types :vrat] {:vrad.argument_type_id :vrat.id})
      (fields :vrad.id :vrad.name :vrad.description [:vrat.name :argument_type])
      (where {:vrad.rule_type_id (uuidify rule-type-id)})
      (order :vrad.argument_index)
      select))

(defn add-validation-rule
  "Adds a validation rule to the database."
  [parameter-id rule-type]
  (insert validation_rules
          (values {:parameter_id parameter-id
                   :rule_type    (subselect rule_type
                                            (fields :id)
                                            (where {:name rule-type
                                                    :deprecated false}))})))

(defn remove-parameter-validation-rules
  "Removes all validation rules and rule arguments associated with the given parameter ID."
  [parameter-id]
  (delete validation_rules (where {:parameter_id parameter-id})))

(defn add-validation-rule-argument
  "Adds a validation rule argument to the database."
  [validation-rule-id ordering argument-value]
  (insert validation_rule_arguments (values {:rule_id validation-rule-id
                                             :ordering ordering
                                             :argument_value argument-value})))

(defn add-parameter-default-value
  "Adds a parameter's default value to the database."
  [parameter-id default-value]
  (insert parameter_values (values {:parameter_id parameter-id
                                    :value        default-value
                                    :is_default   true})))

(defn add-app-parameter-value
  "Adds a parameter value to the database."
  [parameter-value]
  (insert parameter_values (values (filter-valid-parameter-value-values parameter-value))))

(defn remove-parameter-values
  "Removes all parameter values associated with the given parameter IDs."
  [parameter-ids]
  (delete parameter_values (where {:parameter_id [in parameter-ids]})))

(defn app-accessible-by
  "Obtains the list of users who can access an app."
  [app-id]
  (map :username
       (select [:apps :a]
               (join [:app_category_app :aca]
                     {:a.id :aca.app_id})
               (join [:app_categories :g]
                     {:aca.app_category_id :g.id})
               (join [:workspace :w]
                     {:g.workspace_id :w.id})
               (join [:users :u]
                     {:w.user_id :u.id})
               (fields :u.username)
               (where {:a.id app-id}))))

(defn count-external-steps
  "Counts how many steps have an external ID in the given app."
  [app-id]
  ((comp :count first)
   (select [:app_steps :s]
           (aggregate (count :external_app_id) :count)
           (join [:tasks :t] {:s.task_id :t.id})
           (where {:s.app_id (uuidify app-id)})
           (where (raw "t.external_app_id IS NOT NULL")))))

(defn permanently-delete-app
  "Permanently removes an app from the metadata database."
  [app-id]
  (delete/permanently-delete-app ((comp :id get-app) app-id)))

(defn delete-app
  "Marks or unmarks an app as deleted in the metadata database."
  ([app-id]
   (delete-app true app-id))
  ([deleted? app-id]
   (sql/update :app_versions (set-fields {:deleted deleted?}) (where {:app_id app-id}))))

(defn disable-app
  "Marks or unmarks an app as disabled in the metadata database."
  ([app-id]
   (disable-app true app-id))
  ([disabled? app-id]
   (sql/update :app_versions (set-fields {:disabled disabled?}) (where {:app_id app-id}))))

(defn rate-app
  "Adds or updates a user's rating and comment ID for the given app."
  [app-id user-id request]
  (let [rating (first (select ratings (where {:app_id app-id, :user_id user-id})))]
    (if rating
      (sql/update ratings
                  (set-fields (remove-nil-vals request))
                  (where {:app_id app-id
                          :user_id user-id}))
      (insert ratings
              (values (assoc (remove-nil-vals request) :app_id app-id, :user_id user-id))))))

(defn delete-app-rating
  "Removes a user's rating and comment ID for the given app."
  [app-id user-id]
  (delete ratings
          (where {:app_id app-id
                  :user_id user-id})))

(defn get-app-avg-rating
  "Gets the average and total number of user ratings for the given app ID."
  [app-id]
  (first
   (select ratings
           (fields (raw "CAST(COALESCE(AVG(rating), 0.0) AS DOUBLE PRECISION) AS average"))
           (aggregate (count :rating) :total)
           (where {:app_id app-id}))))

(defn load-app-info
  [app-id]
  (first
   (select [:apps :a] (where {:id (uuidify app-id)}))))

(defn load-app-steps
  [app-id]
  (select [:app_versions :v]
          (join [:app_steps :s] {:s.app_version_id :v.id})
          (join [:tasks :t] {:s.task_id :t.id})
          (join [:job_types :jt] {:t.job_type_id :jt.id})
          (fields [:s.id              :step_id]
                  [:t.id              :task_id]
                  [:t.tool_id         :tool_id]
                  [:jt.name           :job_type]
                  [:jt.system_id      :system_id]
                  [:t.external_app_id :external_app_id])
          (where {:v.id (-> app-id uuidify get-app-latest-version)})
          (order :step :ASC)))

(defn- mapping-base-query
  []
  (-> (select* [:workflow_io_maps :wim])
      (join [:input_output_mapping :iom] {:wim.id :iom.mapping_id})
      (fields [:wim.source_step     :source_id]
              [:wim.target_step     :target_id]
              [:iom.input           :input_id]
              [:iom.external_input  :external_input_id]
              [:iom.output          :output_id]
              [:iom.external_output :external_output_id])))

(defn load-target-step-mappings
  [step-id]
  (select (mapping-base-query)
          (where {:wim.target_step step-id})))

(defn load-app-mappings
  [app-id]
  (select (mapping-base-query)
          (where {:wim.app_id (uuidify app-id)})))

(defn load-app-details
  [app-ids]
  (select app_listing
          (where {:id [in (map uuidify app-ids)]})))

(defn get-default-output-name
  [task-id parameter-id]
  (some->> (-> (select* [:tasks :t])
               (join [:parameter_groups :pg] {:t.id :pg.task_id})
               (join [:parameters :p] {:pg.id :p.parameter_group_id})
               (join [:parameter_values :pv] {:p.id :pv.parameter_id})
               (fields [:pv.value :default_value])
               (where {:pv.is_default true
                       :t.id          task-id
                       :p.id          parameter-id})
               (select))
           (first)
           (:default_value)))

(defn- default-value-subselect
  []
  (subselect [:parameter_values :pv]
             (fields [:pv.value :default_value])
             (where {:pv.parameter_id :p.id
                     :pv.is_default   true})))

(defn get-app-parameters
  [app-id]
  (select [:task_param_listing :p]
          (fields :p.id
                  :p.name
                  :p.description
                  :p.label
                  [(default-value-subselect) :default_value]
                  :p.is_visible
                  :p.ordering
                  :p.omit_if_blank
                  [:p.parameter_type :type]
                  :p.value_type
                  :p.is_implicit
                  :p.info_type
                  :p.data_format
                  [:s.id :step_id]
                  [:t.external_app_id :external_app_id])
          (join [:app_steps :s]
                {:s.task_id :p.task_id})
          (join [:tasks :t]
                {:p.task_id :t.id})
          (join [:apps :app]
                {:app.id :s.app_id})
          (where {:app.id (uuidify app-id)})))

(defn get-app-names
  [app-ids]
  (select :apps
          (fields :id :name)
          (where {:id [in (map uuidify app-ids)]})))

(defn get-app-name
  [app-id]
  (->> (select :apps
               (fields :name)
               (where {:id (uuidify app-id)}))
       first
       :name))

(defn- user-favorite-subselect
  [root-category-field faves-idx]
  (subselect [:app_category_group :acg]
             (fields :child_category_id)
             (where {:parent_category_id root-category-field
                     :child_index        faves-idx})))

(defn get-category-id-for-app
  [username app-id faves-idx]
  ((comp :id first)
   (select [:app_category_listing :l]
           (fields :l.id)
           (join [:app_category_app :aca] {:l.id :aca.app_category_id})
           (join [:workspace :w] {:l.workspace_id :w.id})
           (join [:users :u] {:w.user_id :u.id})
           (where (and (or {:l.is_public true}
                           {:u.username username})
                       {:aca.app_id (uuidify app-id)
                        :l.id       [not= (user-favorite-subselect :w.root_category_id faves-idx)]})))))

(defn list-duplicate-apps-by-id
  [app-name app-id-set]
  ; Uses app_listing view so we can check whether all versions are deleted.
  (select :app_listing
          (fields :id :name :description)
          (where {(normalize-string :name) (normalize-string app-name)
                  :deleted                 false
                  :id                      [in app-id-set]})))

(defn- list-duplicate-apps*
  [app-name app-id category-id-set]
  ; Uses app_listing view so we can check whether all versions are deleted.
  (select [:app_listing :a]
          (fields :a.id :a.name :a.description)
          (join [:app_category_app :aca] {:a.id :aca.app_id})
          (where {(normalize-string :a.name) (normalize-string app-name)
                  :a.deleted                 false
                  :aca.app_category_id       [in category-id-set]
                  :a.id                      [not= app-id]})))

(defn- app-category-id-subselect
  [app-id]
  (subselect :app_category_app
             (fields :app_category_id)
             (where {:app_id app-id})))

(defn list-duplicate-apps
  "List apps with the same name that exist in the same category as the new app."
  [app-name app-id category-ids]
  (->> (if (seq category-ids)
         category-ids
         (app-category-id-subselect app-id))
       (list-duplicate-apps* app-name app-id)))

(defn filter-visible-app-ids
  "Filters the given list of app IDs, returning only those not marked as deleted."
  [app-ids]
  (->> (select :apps
               (fields :id)
               (where {:id [in app-ids]
                       :deleted false}))
       (map :id)))

(defn get-resource-requirements-for-task
  [task-id]
  (-> (select* [container-settings :c])
      (join [:tasks :t] {:t.tool_id :c.tools_id})
      (fields :memory_limit
              :min_memory_limit
              :min_cpu_cores
              :max_cpu_cores
              :min_disk_space)
      (where {:t.id task-id})
      select
      first
      remove-nil-vals))

(defn list-app-publication-requests
  [app-id requestor include-completed]
  (-> (select* [:app_publication_requests :apr])
      (join [:users :u] {:apr.requestor_id :u.id})
      (fields :apr.id
              :apr.app_id
              [(sqlfn regexp_replace :u.username "@.*" "") :requestor])
      (conditional-where app-id {:apr.app_id app-id})
      (conditional-where requestor {(sqlfn regexp_replace :u.username "@.*" "") requestor})
      (conditional-where (not include-completed)
                         (not (exists (subselect [:app_publication_request_statuses :aprs]
                                                 (join [:app_publication_request_status_codes :aprsc]
                                                       {:aprs.app_publication_request_status_code_id :aprsc.id})
                                                 (where {:aprs.app_publication_request_id :apr.id
                                                         :aprsc.name                      "Completion"})))))
      select))

(defn mark-app-publication-requests-complete
  [request-ids updater-username]
  (let [status-code-id (get-app-publication-status-code-id "Completion")
        user-id        (get-user-id updater-username)]
    (insert :app_publication_request_statuses
            (values (mapv (fn [request-id]
                            {:app_publication_request_id             request-id
                             :app_publication_request_status_code_id status-code-id
                             :updater_id                             user-id})
                          request-ids)))))
