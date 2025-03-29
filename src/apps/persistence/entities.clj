(ns apps.persistence.entities
  (:require [korma.core :as sql]))

(declare users
         collaborator
         requestor
         workspace
         app_categories
         apps
         app_versions
         app_steps
         app_references
         integration_data
         tools
         tool_test_data_files
         output_mapping
         input_mapping
         tasks
         job_types
         inputs
         outputs
         task_parameters
         info_type
         data_formats
         multiplicity
         parameter_groups
         parameters
         parameter_values
         parameter_types
         value_type
         validation_rules
         validation_rule_arguments
         rule_type
         rule_subtype
         app_category_listing
         app_listing
         tool_listing
         ratings
         collaborators
         genome_reference
         created_by
         last_modified_by
         data_source
         tool_types
         tool_request_status_codes
         tool_architectures
         tool_requests
         apps_htcondor_extra
         tool_request_statuses
         container-images
         container-settings
         container-devices
         container-volumes
         container-volumes-from)

;; Users who have logged into the DE.  Multiple entities are associated with
;; the same table in order to allow us to have multiple relationships between
;; the same two tables.
(sql/defentity users
  (sql/has-one workspace {:fk :user_id})
  (sql/has-many ratings {:fk :user_id}))
(sql/defentity collaborator
  (sql/table :users :collaborator)
  (sql/has-many collaborators {:fk :collaborator_id}))

;; The workspaces of users who have logged into the DE.
(sql/defentity workspace
  (sql/belongs-to users {:fk :user_id})
  (sql/belongs-to app_categories {:fk :root_category_id}))

;; An app group.
(sql/defentity app_categories
  (sql/belongs-to workspace)
  (sql/many-to-many app_categories :app_category_group
                {:lfk :parent_category_id
                 :rfk :child_category_id})
  (sql/many-to-many apps :app_category_app
                {:lfk :app_category_id
                 :rfk :app_id}))

;; An app.
(sql/defentity apps
  (sql/has-many app_versions {:fk :app_id})
  (sql/many-to-many app_categories :app_category_app
                {:lfk :app_id
                 :rfk :app_category_id})
  (sql/has-many ratings {:fk :app_id}))

;; Versions of an app.
(sql/defentity app_versions
  (sql/belongs-to integration_data)
  (sql/has-many app_references {:fk :app_version_id})
  (sql/many-to-many tasks :app_steps
                {:lfk :app_version_id
                 :rfk :task_id}))

;; References associated with an app.
(sql/defentity app_references)

;; Extra info for HTCondor
(sql/defentity apps_htcondor_extra)

;; Information about who integrated an app or a deployed component.
(sql/defentity integration_data
  (sql/has-many app_versions)
  (sql/has-many tools))

(sql/defentity data-containers
  (sql/table :data_containers)
  (sql/has-one container-volumes-from)
  (sql/belongs-to container-images))

;; Information about containers containing tools.
(sql/defentity container-images
  (sql/table :container_images)
  (sql/has-one data-containers))

(sql/defentity ports
  (sql/table :container_ports :ports)
  (sql/belongs-to container-settings))

(sql/defentity interapps-proxy-settings
  (sql/table :interactive_apps_proxy_settings)
  (sql/belongs-to container-settings))

(sql/defentity container-settings
  (sql/table :container_settings)
  (sql/belongs-to tools)
  (sql/has-many container-devices)
  (sql/has-many container-volumes)
  (sql/has-many container-volumes-from)
  (sql/has-many ports)
  (sql/has-one interapps-proxy-settings))

(sql/defentity container-devices
  (sql/table :container_devices)
  (sql/belongs-to container-settings))

(sql/defentity container-volumes
  (sql/table :container_volumes)
  (sql/belongs-to container-settings))

(sql/defentity container-volumes-from
  (sql/table :container_volumes_from)
  (sql/belongs-to container-settings)
  (sql/belongs-to data-containers))

;; Information about a deployed tool.
(sql/defentity tools
  (sql/belongs-to integration_data)
  (sql/belongs-to tool_types {:fk :tool_type_id})
  (sql/has-many tool_test_data_files {:fk :tool_id})
  (sql/has-many tool_requests {:fk :tool_id})
  (sql/has-one container-settings))

;; Test data files for use with deployed components.
(sql/defentity tool_test_data_files
  (sql/belongs-to tools {:fk :tool_id}))

;; Steps within an app.
(sql/defentity app_steps
  (sql/has-many output_mapping {:fk :source_step})
  (sql/has-many input_mapping {:fk :target_step}))

;; A table that maps outputs from one step to inputs to another set.  Two
;; entities are associated with a single table here for convenience.  when I
;; have more time, I'd like to try to improve the relation handling in Korma
;; so that multiple relationships with the same table work correctly.
(sql/defentity output_mapping
  (sql/table :workflow_io_maps :output_mapping))
(sql/defentity input_mapping
  (sql/table :workflow_io_maps :input_mapping))

;; Data object mappings can't be implemeted as entities until Korma supports
;; composite primary keys.  In the meantime, we'll have to deal with this table
;; in code.

;; A job type indicates primarily where a job will be executed.
(sql/defentity job_types
  (sql/table :job_types))

;; A task defines an interface to a tool that can be called.
(sql/defentity tasks
  (sql/has-many parameter_groups {:fk :task_id})
  (sql/has-many inputs {:fk :task_id})
  (sql/has-many outputs {:fk :task_id})
  (sql/has-many task_parameters {:fk :task_id})
  (sql/belongs-to job_types {:fk :job_type_id}))

;; Input and output definitions. Once again, multiple entities are associated
;; with the same table to allow us to define multiple relationships between
;; the same two tables.
(sql/defentity inputs
  (sql/table (sql/subselect :task_param_listing (sql/where {:value_type "Input"})) :inputs))
(sql/defentity outputs
  (sql/table (sql/subselect :task_param_listing (sql/where {:value_type "Output"})) :outputs))
(sql/defentity task_parameters
  (sql/table :task_param_listing :task_parameters))

;; File parameters.
(sql/defentity file_parameters
  (sql/belongs-to info_type {:fk :info_type})
  (sql/belongs-to data_formats {:fk :data_format})
  (sql/belongs-to multiplicity {:fk :multiplicity})
  (sql/belongs-to data_source {:fk :data_source_id}))

;; The type of information stored in a data object.
(sql/defentity info_type)

;; The format of the data in a data object.
(sql/defentity data_formats)

;; An input or output multiplicity definition.
(sql/defentity multiplicity)

;; A group of parameters.
(sql/defentity parameter_groups
  (sql/has-many parameters {:fk :parameter_group_id}))

;; A single parameter.
(sql/defentity parameters
  (sql/has-many parameter_values {:fk :parameter_id})
  (sql/has-many validation_rules {:fk :parameter_id})
  (sql/has-one file_parameters {:fk :parameter_id})
  (sql/belongs-to parameter_types {:fk :parameter_type})
  (sql/many-to-many tool_types :tool_type_parameter_type
                {:lfk :parameter_type_id
                 :rfk :tool_type_id}))

(sql/defentity parameter_values)

;; The type of a single parameter.
(sql/defentity parameter_types
  (sql/belongs-to value_type))

;; The type of value associated with a parameter.  This is used to determine
;; which rule types may be associated with a parameter.
(sql/defentity value_type
  (sql/has-one parameter_types)
  (sql/many-to-many rule_type :rule_type_value_type
                {:lfk :value_type_id
                 :rfk :rule_type_id}))

;; Validation Rules are used to describe individual validation steps for a parameter.
(sql/defentity validation_rules
  (sql/has-many validation_rule_arguments {:fk :rule_id})
  (sql/belongs-to rule_type {:fk :rule_type}))

;; Rule types indicate the validation method to use.
(sql/defentity rule_type
  (sql/belongs-to rule_subtype)
  (sql/many-to-many value_type :rule_type_value_type
                {:lfk :rule_type_id}
                {:rfk :value_type_id}))

;; Rule arguments will have to be handled in code until Korma can be enhanced
;; to accept composite primary keys.
(sql/defentity validation_rule_arguments)

;; Rule subtypes are used to distinguish different flavors of values that
;; rules can be applied to.  For example, Number value types are segregated
;; into Integer and Double subtypes.
(sql/defentity rule_subtype)

;; A view used to list app categories.
(sql/defentity app_category_listing
  (sql/many-to-many app_category_listing :app_category_group
                {:lfk :parent_category_id
                 :rfk :child_category_id})
  (sql/many-to-many app_listing :app_category_app
                {:lfk :app_category_id
                 :rfk :app_id}))

;; A view used to list apps.
(sql/defentity app_listing
  (sql/has-many tool_listing {:fk :app_id})
  (sql/has-many ratings {:fk :app_id}))

;; A view used to list tools.
(sql/defentity tool_listing)

;; Application ratings.
(sql/defentity ratings
  (sql/belongs-to users {:fk :user_id})
  (sql/belongs-to apps {:fk :app_id}))

;; Database version entries.
(sql/defentity version
  (sql/pk :version))

;; Associates users with other users for collaboration.
(sql/defentity collaborators
  (sql/belongs-to users {:fk :user_id})
  (sql/belongs-to collaborator {:fk :collaborator_id}))

;; Contains genomic metadata.
(sql/defentity genome_reference
  (sql/belongs-to created_by {:fk :created_by})
  (sql/belongs-to last_modified_by {:fk :last_modified_by}))
(sql/defentity created_by
  (sql/table :users :created_by)
  (sql/has-one genome_reference {:fk :created_by}))
(sql/defentity last_modified_by
  (sql/table :users :last_modified_by)
  (sql/has-one genome_reference {:fk :last_modified_by}))

;; Data source.
(sql/defentity data_source)

;; Tool types.
(sql/defentity tool_types
  (sql/many-to-many parameter_types :tool_type_parameter_type
                {:lfk :tool_type_id
                 :rfk :parameter_type_id}))

;; Tool request status codes.
(sql/defentity tool_request_status_codes
  (sql/has-many tool_request_statuses {:fk :tool_request_status_code_id}))

;; Tool architectures.
(sql/defentity tool_architectures
  (sql/has-many tool_requests {:fk :tool_architecture_id}))

;; The user who submitted a tool request.
(sql/defentity requestor
  (sql/table :users :requestor)
  (sql/has-many tool_requests {:fk :requestor_id}))

;; Tool requests.
(sql/defentity tool_requests
  (sql/belongs-to requestor {:fk :requestor_id})
  (sql/belongs-to tool_architectures {:fk :tool_architecture_id})
  (sql/belongs-to tools {:fk :tool_id})
  (sql/has-many tool_request_statuses {:fk :tool_request_id}))

;; The user who updated a tool request.
(sql/defentity updater
  (sql/table :users :updater)
  (sql/has-many tool_request_statuses {:fk :updater_id}))

;; Tool request status changes.
(sql/defentity tool_request_statuses
  (sql/belongs-to tool_requests {:fk :tool_request_id})
  (sql/belongs-to tool_request_status_codes {:fk :tool_request_status_code_id})
  (sql/belongs-to updater {:fk :updater_id}))

(sql/defentity user-preferences
  (sql/table :user_preferences)
  (sql/belongs-to users {:fk :user_id}))

(sql/defentity user-sessions
  (sql/table :user_sessions)
  (sql/belongs-to users {:fk :user_id}))

(sql/defentity job-status-updates
  (sql/table :job_status_updates))

;; Docker registry auth information
(sql/defentity docker-registries
  (sql/table :docker_registries)
  (sql/entity-fields :name :username :password)
  (sql/pk :name))
