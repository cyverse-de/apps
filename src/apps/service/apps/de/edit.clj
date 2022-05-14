(ns apps.service.apps.de.edit
  (:use [apps.metadata.params :only [format-reference-genome-value]]
        [apps.persistence.app-groups :only [add-app-to-category get-app-subcategory-id]]
        [apps.persistence.entities]
        [apps.service.apps.de.validation :only [verify-app-editable verify-app-permission validate-app-name]]
        [apps.util.config :only [workspace-dev-app-category-index]]
        [apps.util.conversions :only [remove-nil-vals convert-rule-argument]]
        [apps.util.db :only [transaction]]
        [apps.validation :only [validate-parameter]]
        [apps.workspace :only [get-workspace]]
        [clojure.string :only [blank?]]
        [clojure-commons.validators :only [user-owns-app?]]
        [kameleon.uuids :only [uuidify]]
        [korma.core :exclude [update]]
        [slingshot.slingshot :only [throw+]])
  (:require [apps.clients.permissions :as permissions]
            [apps.persistence.app-metadata :as persistence]
            [apps.persistence.app-metadata.relabel :as relabel]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.de.categorization :as categorization]
            [apps.service.apps.de.constants :as c]
            [apps.service.apps.jobs.util :as job-util]
            [clojure.set :as set]
            [clojure-commons.exception-util :as cxu]))

(def ^:private copy-prefix "Copy of ")
(def ^:private max-app-name-len 255)

(defn- get-app-details
  "Retrieves the details for a single-step app."
  [app-id]
  (-> (select* apps)
      (fields :id
              :name
              :description)
      (where {:id app-id})
      (with app_versions
            (fields :id
                    :version
                    :integration_date
                    :edited_date)
            (where {:app_versions.id (persistence/get-app-latest-version app-id)})
            (with app_references)
            (with tasks
                  (fields :id)
                  (with parameter_groups
                        (order :display_order)
                        (fields :id
                                :name
                                :description
                                :label
                                [:is_visible :isVisible])
                        (with parameters
                              (order :display_order)
                              (with file_parameters
                                    (with info_type)
                                    (with data_formats)
                                    (with data_source))
                              (with parameter_types
                                    (with value_type))
                              (with validation_rules
                                    (with rule_type)
                                    (with validation_rule_arguments
                                          (order :ordering)
                                          (fields :argument_value))
                                    (fields :id
                                            [:rule_type.name :type]
                                            [:rule_type.id :type_id])
                                    (where {:rule_type.deprecated false}))
                              (with parameter_values
                                    (fields :id
                                            :parent_id
                                            :name
                                            :value
                                            [:label :display]
                                            :description
                                            [:is_default :isDefault])
                                    (order [:parent_id :display_order] :ASC))
                              (fields :id
                                      :name
                                      :label
                                      :description
                                      [:ordering :order]
                                      :required
                                      [:is_visible :isVisible]
                                      :omit_if_blank
                                      [:parameter_types.name :type]
                                      [:value_type.name :value_type]
                                      [:info_type.name :file_info_type]
                                      :file_parameters.is_implicit
                                      :file_parameters.repeat_option_flag
                                      :file_parameters.retain
                                      [:data_source.name :data_source]
                                      [:data_formats.name :format])))))
      (select)
      first))

(defn- format-validator
  [validator]
  {:type (:type validator)
   :params (mapv convert-rule-argument
                 (map :argument_value (:validation_rule_arguments validator))
                 (map :argument_type (persistence/get-rule-arg-definitions (:type_id validator))))})

(defn- format-param-value
  [param-value]
  (remove-nil-vals
   (dissoc param-value :parent_id)))

(defn- format-tree-param-children
  [param-map group]
  (let [id (:id group)
        [groups args] ((juxt filter remove) #(get param-map (:id %)) (get param-map id))
        groups (map format-param-value groups)
        args (map format-param-value args)]
    (assoc group
           :groups (map (partial format-tree-param-children param-map) groups)
           :arguments args)))

(defn- format-tree-params
  [param-values]
  (let [param-map (group-by :parent_id param-values)
        root (first (get param-map nil))
        root {:id (:id root)
              :selectionCascade (:name root)
              :isSingleSelect (:isDefault root)}]
    (format-tree-param-children param-map root)))

(defn- format-list-type-params
  [param-type param-values]
  (if (= param-type persistence/param-tree-type)
    [(format-tree-params param-values)]
    (map format-param-value param-values)))

(defn- format-list-param
  [param param-values]
  (let [param-type (:type param)
        param-args (format-list-type-params param-type param-values)
        param (if-not (= param-type persistence/param-tree-type)
                (assoc param :defaultValue (first (filter :isDefault param-args)))
                param)]
    (assoc param :arguments param-args)))

(defn- format-file-params
  "Returns param with a file_parameters key/value map added if the param type matches one in the
   persistence/param-file-types set, but not the persistence/param-input-reference-types set.
   Only includes the repeat_option_flag key in the file_parameters map if the param type is also the
   persistence/param-multi-input-type string."
  [{param-type :type :as param}]
  (let [file-param-keys [:format
                         :file_info_type
                         :is_implicit
                         :data_source
                         :retain]
        file-param-keys (if (= persistence/param-multi-input-type param-type)
                          (conj file-param-keys :repeat_option_flag)
                          file-param-keys)]
    (if (contains?
         (set/difference persistence/param-file-types persistence/param-input-reference-types)
         param-type)
      (assoc param :file_parameters (select-keys param file-param-keys))
      param)))

(defn- format-default-value
  [{param-type :type :as param} default-value]
  (assoc param
         :defaultValue
         (when default-value
           (cond
             (contains? persistence/param-reference-genome-types param-type)
             (format-reference-genome-value default-value)

             (job-util/input-type? param-type)
             {:path default-value}

             :else
             default-value))))

(defn- format-param
  [{param-type :type
    value-type :value_type
    param-values :parameter_values
    validation-rules :validation_rules
    :as param}]
  (when-not value-type
    (throw+ {:type  :clojure-commons.exception/not-writeable
             :error "App contains Parameters that cannot be copied or modified at this time."}))
  (let [param (-> param
                  format-file-params
                  (assoc :validators (map format-validator validation-rules))
                  (dissoc :value_type
                          :parameter_values
                          :validation_rules
                          :format
                          :file_info_type
                          :is_implicit
                          :repeat_option_flag
                          :data_source
                          :retain)
                  remove-nil-vals)]
    (if (contains? persistence/param-list-types param-type)
      (format-list-param param param-values)
      (format-default-value param (-> param-values first :value)))))

(defn- format-group
  [group]
  (remove-nil-vals
   (update-in group [:parameters] (partial map format-param))))

(defn- format-app-tool
  [tool]
  (remove-nil-vals (select-keys tool [:id :name :description :location :type :version :attribution :deprecated])))

(defn- format-app-for-editing
  [app]
  (let [{:keys [app_versions] :as app} (get-app-details (:id app))
        {:keys [app_references tasks]
         :as   version-details}        (first app_versions)
        task                           (first tasks)]
    (when (empty? task)
      (throw+ {:type  :clojure-commons.exception/not-writeable
               :error "App contains no steps and cannot be copied or modified."}))
    (remove-nil-vals
     (-> app
         (merge (select-keys version-details [:version
                                              :edited_date
                                              :integration_date]))
         (assoc :version_id (:id version-details)
                :references (map :reference_text app_references)
                :tools      (map format-app-tool (persistence/get-app-tools (:id app)))
                :groups     (map format-group (:parameter_groups task))
                :system_id  c/system-id)
         (dissoc :app_versions)))))

(defn get-app-ui
  "This service prepares a JSON response for editing an App in the client."
  [user app-id]
  (let [app (persistence/get-app app-id)]
    (when-not (user-owns-app? user app)
      (verify-app-permission user app "write"))
    (format-app-for-editing app)))

(defn- update-parameter-argument
  "Adds a selection parameter's argument, and any of its child arguments and groups."
  [param-id parent-id display-order {param-value-id :id
                                     groups         :groups
                                     arguments      :arguments
                                     :as            parameter-value}]
  (let [insert-values (remove-nil-vals
                       (assoc parameter-value :id (uuidify param-value-id)
                              :parameter_id param-id
                              :parent_id parent-id
                              :display_order display-order))
        param-value-id (:id (persistence/add-app-parameter-value insert-values))
        update-sub-arg-mapper (partial update-parameter-argument param-id param-value-id)]
    (remove-nil-vals
     (assoc parameter-value
            :id        param-value-id
            :arguments (when arguments (doall (map-indexed update-sub-arg-mapper arguments)))
            :groups    (when groups    (doall (map-indexed update-sub-arg-mapper groups)))))))

(defn- update-parameter-tree-root
  "Adds a tree selection parameter's root and its child arguments and groups."
  [param-id {name :selectionCascade is-default :isSingleSelect :as root}]
  (let [root (update-parameter-argument param-id nil 0 (assoc root :name name :is_default is-default))]
    (dissoc root :name :is_default)))

(defn- update-param-selection-arguments
  "Adds a selection parameter's arguments."
  [param-type param-id arguments]
  (if (= persistence/param-tree-type param-type)
    [(update-parameter-tree-root param-id (first arguments))]
    (doall (map-indexed (partial update-parameter-argument param-id nil) arguments))))

(defn- format-file-parameter-for-save
  "Formats an App parameter's file settings for saving to the db."
  [param-id param-type {:keys [retain]
                        :or {retain (contains? persistence/param-output-types param-type)}
                        :as file-parameter}]
  (remove-nil-vals
   (if (contains? persistence/param-input-reference-types param-type)
     {:parameter_id   param-id
      :file_info_type param-type
      :format         "Unspecified"
      :data_source    "file"}
     (assoc file-parameter :parameter_id param-id
            :retain retain))))

(defn- get-rule-type
  "Gets information about a named rule type."
  [rule-type-name]
  (let [rule-type (persistence/get-rule-type rule-type-name)]
    (when (nil? rule-type)
      (cxu/bad-request (str "validation rule type " rule-type-name " not found")))
    (when (:deprecated rule-type)
      (cxu/bad-request (str "validation rule type " rule-type-name " is deprecated")))
    rule-type))

(defn- validate-rule-arg-not-nil [arg]
  (when (nil? arg)
    (cxu/bad-request (str "nil rule arguments are not allowed"))))

(defn- validate-integer-arg-value [arg]
  (try
    (Integer/parseInt (str arg))
    (catch NumberFormatException _
      (cxu/bad-request (str "invalid integer argument value: " arg)))))

(defn- validate-double-arg-value [arg]
  (try
    (Double/parseDouble (str arg))
    (catch NumberFormatException _
      (cxu/bad-request (str "invalid double argument value: " arg)))))

(def ^:private validate-string-arg validate-rule-arg-not-nil)
(def ^:private validate-integer-arg (juxt validate-rule-arg-not-nil validate-integer-arg-value))
(def ^:private validate-double-arg (juxt validate-rule-arg-not-nil validate-double-arg-value))

(defn- validate-rule-arg
  "Validates a single rule argument."
  [{type :argument_type :as arg-def} arg]
  (condp = type
    "String"  (validate-string-arg arg)
    "Integer" (validate-integer-arg arg)
    "Double"  (validate-double-arg arg)
    (cxu/internal-system-error (str "unsupported argument type found in database: " type))))

(defn- validate-rule-args
  "Verifies the number and types of a validator rule argument."
  [validator-type rule-args]
  (let [rule-type (get-rule-type validator-type)
        arg-defs  (persistence/get-rule-arg-definitions (:id rule-type))]
    (when (not= (count rule-args) (count arg-defs))
      (cxu/bad-request (str "incorrect number of arguments (" (count rule-args) ") for rule type " validator-type)))
    (dorun (map validate-rule-arg arg-defs rule-args))))

(defn- add-validation-rule
  "Adds an App parameter's validator and its rule arguments."
  [parameter-id {validator-type :type rule-args :params}]
  (validate-rule-args validator-type rule-args)
  (let [validation-rule-id (:id (persistence/add-validation-rule parameter-id validator-type))]
    (dorun (map-indexed (partial persistence/add-validation-rule-argument validation-rule-id)
                        rule-args))))

(defn- get-parameter-default-value
  "Gets the default value from an incoming parameter."
  [{default-value :defaultValue param-type :type}]
  (cond
    (job-util/input-type? param-type)
    (:path default-value)

    (contains? persistence/param-list-types param-type)
    (:id default-value)

    :else
    default-value))

(defn- update-app-parameter
  "Adds or updates an App parameter and any associated file parameters, validators, and arguments."
  [task-id group-id display-order {param-id :id
                                   param-type :type
                                   file-parameter :file_parameters
                                   validators :validators
                                   arguments :arguments
                                   visible :isVisible
                                   :or {visible true}
                                   :as parameter}]
  (validate-parameter parameter)
  (let [update-values (assoc parameter :parameter_group_id group-id
                             :display_order display-order
                             :isVisible visible)
        param-exists (and param-id (persistence/get-app-parameter param-id task-id))
        param-id (if param-exists
                   param-id
                   (:id (persistence/add-app-parameter update-values)))
        parameter (assoc parameter :id param-id)
        default-value (get-parameter-default-value parameter)]
    (when param-exists
      (persistence/update-app-parameter update-values)
      (persistence/remove-file-parameter param-id)
      (persistence/remove-parameter-validation-rules param-id)
      (when-not (contains? persistence/param-file-types param-type)
        (persistence/remove-parameter-mappings param-id)))

    (when-not (or (contains? persistence/param-list-types param-type) (blank? (str default-value)))
      (persistence/add-parameter-default-value param-id default-value))

    (dorun (map (partial add-validation-rule param-id) validators))

    (when (contains? persistence/param-file-types param-type)
      (persistence/add-file-parameter
       (format-file-parameter-for-save param-id param-type file-parameter)))

    (remove-nil-vals
     (assoc parameter
            :arguments (when (contains? persistence/param-list-types param-type)
                         (update-param-selection-arguments param-type param-id arguments))))))

(defn- update-app-group
  "Adds or updates an App group and its parameters."
  [task-id display-order {group-id :id parameters :parameters :as group}]
  (let [update-values (assoc group :task_id task-id :display_order display-order)
        group-exists (and group-id (persistence/get-app-group group-id task-id))
        group-id (if group-exists
                   group-id
                   (:id (persistence/add-app-group update-values)))]
    (when group-exists
      (persistence/update-app-group update-values))
    (assoc group
           :id group-id
           :parameters (doall (map-indexed (partial update-app-parameter task-id group-id) parameters)))))

(defn- delete-app-parameter-orphans
  "Deletes parameters no longer associated with an App group."
  [{group-id :id params :parameters}]
  (let [parameter-ids (remove nil? (map :id params))]
    (if (empty? parameter-ids)
      (persistence/clear-group-parameters group-id)
      (persistence/remove-parameter-orphans group-id parameter-ids))))

(defn- delete-app-orphans
  "Deletes groups and parameters no longer associated with an App."
  [task-id groups]
  (let [group-ids (remove nil? (map :id groups))]
    (when-not (empty? group-ids)
      (persistence/remove-app-group-orphans task-id group-ids)
      (dorun (map delete-app-parameter-orphans groups)))))

(defn- update-app-groups
  "Adds or updates the given App groups under the given App task ID."
  [task-id groups]
  (let [updated-groups (doall (map-indexed (partial update-app-group task-id) groups))]
    (delete-app-orphans task-id updated-groups)
    updated-groups))

(defn- validate-updated-app-name
  [username app-id app-name]
  (categorization/validate-app-name-in-current-hierarchy username app-id app-name)
  (validate-app-name app-name app-id))

(defn update-app
  "This service will update a single-step App, including the information at its top level and the
   tool used by its single task, as long as the App has not been submitted for public use."
  [user {app-id :id app-name :name :keys [references groups] :as app}]
  (verify-app-editable user (persistence/get-app app-id))
  (transaction
   (validate-updated-app-name (:shortUsername user) app-id app-name)
   (persistence/update-app app)
   (let [tool-id (->> app :tools first :id)
         {version-id :id :keys [tasks]} (->> (get-app-details app-id) :app_versions first)
         app-task (first tasks)
         task-id (:id app-task)
         current-param-ids (map :id (mapcat :parameters (:parameter_groups app-task)))]
     (persistence/update-app-version (assoc app :version_id version-id))
      ;; Copy the App's current name, description, and tool ID to its task
     (persistence/update-task jp/de-client-name (assoc app :id task-id :tool_id tool-id))
      ;; CORE-6266 prevent duplicate key errors from reused param value IDs
     (when-not (empty? current-param-ids)
       (persistence/remove-parameter-values current-param-ids))
     (when-not (empty? references)
       (persistence/set-app-references app-id references))
     (update-app-groups task-id groups))
   (get-app-ui user app-id)))

(defn get-user-subcategory
  [username index]
  (-> (get-workspace username)
      (:root_category_id)
      (get-app-subcategory-id index)))

(defn add-app-to-user-dev-category
  "Adds an app with the given ID to the current user's apps-under-development category."
  [{:keys [username]} app-id]
  (add-app-to-category app-id (get-user-subcategory username (workspace-dev-app-category-index))))

(defn- add-single-step-task
  "Adds a task as a single step to the given app, using the app's name, description, and label."
  [{version-id :version_id :as app}]
  (let [task (persistence/add-task jp/de-client-name app)]
    (persistence/add-step version-id 0 {:task_id (:id task)})
    task))

(defn- add-app-version*
  "Adds a single-step app version to an existing app."
  [user {app-id :id :keys [references groups] :as app}]
  (transaction
   (let [version-id (:id (persistence/add-app-version (-> app (dissoc :id) (assoc :app_id app-id)) user))
         tool-id    (->> app :tools first :id)
         task-id    (-> (assoc app :version_id version-id :tool_id tool-id)
                        (add-single-step-task)
                        :id)]
     (when-not (empty? references)
       (persistence/set-app-references version-id references))
     (dorun (map-indexed (partial update-app-group task-id) groups))
     version-id)))

(defn add-app
  "This service will add a single-step App, including the information at its top level."
  [{:keys [username] :as user} {app-name :name :as app}]
  (transaction
    (->> (get-user-subcategory username (workspace-dev-app-category-index))
         vector
         (validate-app-name app-name nil))
   (let [app-id (:id (persistence/add-app app))]
     (add-app-version* user (assoc app :id app-id))
     (add-app-to-user-dev-category user app-id)
     (permissions/register-private-app (:shortUsername user) app-id)
     (get-app-ui user app-id))))

(defn add-app-version
  "Adds a single-step app version to an existing app, if the user has write permission on that app."
  [user {app-id :id app-name :name :as app} admin?]
  (let [existing-app (persistence/get-app app-id)]
    (verify-app-permission user existing-app "write" admin?)
    (transaction
      (validate-updated-app-name (:shortUsername user) app-id app-name)
      (persistence/update-app app)
      (->> app-id
           persistence/get-app-max-version-order
           inc
           (assoc app :id app-id :version_order)
           (add-app-version* user))
      (get-app-ui user app-id))))

(defn- name-too-long?
  "Determines if a name is too long to be extended for a copy name."
  [original-name]
  (> (+ (count copy-prefix) (count original-name)) max-app-name-len))

(defn- already-copy-name?
  "Determines if the name of an app is already a copy name."
  [original-name]
  (.startsWith original-name copy-prefix))

(defn app-copy-name
  "Determines the name of a copy of an app."
  [original-name]
  (cond (name-too-long? original-name)     original-name
        (already-copy-name? original-name) original-name
        :else                              (str copy-prefix original-name)))

(defn- convert-parameter-argument-to-copy
  [{arguments :arguments groups :groups :as parameter-argument}]
  (-> parameter-argument
      (dissoc :id)
      (assoc :arguments (map convert-parameter-argument-to-copy arguments)
             :groups    (map convert-parameter-argument-to-copy groups))
      (remove-nil-vals)))

(defn- convert-app-parameter-to-copy
  [{arguments :arguments :as parameter}]
  (-> parameter
      (dissoc :id)
      (assoc :arguments (map convert-parameter-argument-to-copy arguments))
      (remove-nil-vals)))

(defn- convert-app-group-to-copy
  [{parameters :parameters :as group}]
  (-> group
      (dissoc :id)
      (assoc :parameters (map convert-app-parameter-to-copy parameters))
      (remove-nil-vals)))

(defn- convert-app-to-copy
  "Removes ID fields from a client formatted App, its groups, parameters, and parameter arguments,
   and formats appropriate app fields to prepare it for saving as a copy."
  [app]
  (let [app (format-app-for-editing app)]
    (-> app
        (dissoc :id :version :version_id)
        (assoc :name   (app-copy-name (:name app))
               :groups (map convert-app-group-to-copy (:groups app)))
        (remove-nil-vals))))

(defn copy-app
  "This service makes a copy of an App available in Tito for editing."
  [user app-id]
  (let [app (persistence/get-app app-id)]
    (verify-app-permission user app "read")
    (add-app user (convert-app-to-copy app))))

(defn relabel-app
  "This service allows labels to be updated in any app, whether or not the app has been submitted
   for public use."
  [user {app-name :name app-id :id :as body}]
  (let [app (persistence/get-app app-id)]
    (when-not (user-owns-app? user app)
      (verify-app-permission user app "write")))
  (transaction
   (validate-updated-app-name (:shortUsername user) app-id app-name)
   (relabel/update-app-labels body))
  (get-app-ui user app-id))
