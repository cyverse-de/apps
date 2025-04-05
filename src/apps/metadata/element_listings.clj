(ns apps.metadata.element-listings
  (:require [apps.clients.permissions :as perms-client]
            [apps.persistence.app-metadata :refer [parameter-types-for-tool-type]]
            [apps.persistence.entities :as entities]
            [apps.persistence.tools :as tools-db]
            [apps.tools :refer [format-tool-listing]]
            [apps.util.conversions :refer [remove-nil-vals]]
            [korma.core :as sql]
            [slingshot.slingshot :refer [throw+]]))

(defn get-tool-type-by-name
  "Searches for the tool type with the given name."
  [tool-type-name]
  (first (sql/select entities/tool_types
                     (sql/where {:name tool-type-name}))))

(defn get-tool-type-by-component-id
  "Searches for the tool type associated with the given deployed component."
  [component-id]
  (first (sql/select entities/tools
                     (sql/fields :tool_types.id :tool_types.name :tool_types.label
                                 :tool_types.description)
                     (sql/join entities/tool_types)
                     (sql/where {:tools.id component-id}))))

(defn- base-parameter-type-query
  "Creates the base query used to list parameter types for the metadata element listing service."
  []
  (-> (sql/select* entities/parameter_types)
      (sql/fields :parameter_types.id :parameter_types.name
                  [:value_type.name :value_type] :parameter_types.description)
      (sql/join entities/value_type)
      (sql/where {:deprecated false})
      (sql/order :display_order)))

(defn- get-tool-type-id
  "Gets the internal identifier associated with a tool type name."
  [tool-type-name]
  (let [result (get-tool-type-by-name tool-type-name)]
    (when (nil? result)
      (throw+ {:type ::unknown_tool_type
               :name tool-type-name}))
    (:id result)))

(defn- get-tool-type-for-tool-id
  "Gets the tool type associated with the given tool identifier."
  [tool-id]
  (let [result (get-tool-type-by-component-id tool-id)]
    (when (nil? result)
      (throw+ {:type ::unknown_tool
               :id   tool-id}))
    (:id result)))

(defn- get-tool-type
  "Gets the tool type to use when listing property types.  If the tool type is
   specified directly then we'll use that in the query.  If the deployed
   component is specified then its associated tool type will be used in the
   query.  Otherwise, all property types will be listed."
  [tool-type tool-id]
  (cond (not (nil? tool-type)) (get-tool-type-id tool-type)
        (not (nil? tool-id))   (get-tool-type-for-tool-id tool-id)
        :else                  nil))

(defn- list-data-formats
  "Obtains a listing of data formats known to the DE."
  [_]
  {:formats
   (map remove-nil-vals
        (sql/select entities/data_formats
                    (sql/fields :id :name :label)
                    (sql/order :display_order)))})

(defn- list-data-sources
  "Obtains a listing of data sources."
  [_]
  {:data_sources
   (sql/select entities/data_source
               (sql/fields :id :name :label :description)
               (sql/order :display_order))})

(defn- list-tools
  "Obtains a listing of tools for the metadata element listing service."
  [{:keys [user] :as params}]
  (let [perms           (perms-client/load-tool-permissions user)
        tool-ids        (set (keys perms))
        public-tool-ids (perms-client/get-public-tool-ids)
        tools           (-> params
                            (select-keys [:include-hidden])
                            (assoc :tool-ids   tool-ids
                                   :deprecated false)
                            (tools-db/get-tool-listing))]
    {:tools (map (partial format-tool-listing perms public-tool-ids) tools)}))

(defn- list-info-types
  "Obtains a listing of information types for the metadata element listing service."
  [_]
  {:info_types
   (map remove-nil-vals
        (sql/select entities/info_type
                    (sql/fields :id :name :label)
                    (sql/where {:deprecated false})
                    (sql/order :display_order)))})

(defn- list-property-types
  "Obtains the property types for the metadata element listing service.
   Parameter types may be filtered by tool type or tool.  If the tool type is specified only
   parameter types that are associated with that tool type will be listed.  If the tool is specified
   then only parameter tpes associated with the tool type that is associated with the tool will be
   listed.  Specifying an invalid tool type name or tool id will result in an error."
  [{:keys [tool-type tool-id]}]
  (let [tool-type-id (get-tool-type tool-type tool-id)]
    {:parameter_types
     (map remove-nil-vals
          (if (nil? tool-type-id)
            (sql/select (base-parameter-type-query))
            (parameter-types-for-tool-type (base-parameter-type-query) tool-type-id)))}))

(defn- list-rule-types
  "Obtains the list of rule types for the metadata element listing service."
  [_]
  {:rule_types
   (mapv
    (fn [m]
      (remove-nil-vals
       (assoc (dissoc m :value_type)
              :value_types             (mapv :name (:value_type m))
              :rule_description_format (:rule_description_format m ""))))
    (sql/select entities/rule_type
                (sql/fields [:rule_type.id :id]
                            [:rule_type.name :name]
                            [:rule_type.description :description]
                            [:rule_subtype.name :subtype]
                            [:rule_type.rule_description_format :rule_description_format])
                (sql/join entities/rule_subtype)
                (sql/with entities/value_type)))})

(defn- list-tool-types
  "Obtains the list of tool types for the metadata element listing service."
  [_]
  {:tool_types (map remove-nil-vals (sql/select entities/tool_types (sql/fields :id :name :label :description)))})

(defn- list-value-types
  "Obtains the list of value types for the metadata element listing service."
  [_]
  {:value_types
   (sql/select entities/value_type
               (sql/fields :id :name :description))})

(def ^:private listing-fns
  "The listing functions to use for various metadata element types."
  {"data-sources"    list-data-sources
   "file-formats"    list-data-formats
   "info-types"      list-info-types
   "parameter-types" list-property-types
   "rule-types"      list-rule-types
   "tools"           list-tools
   "tool-types"      list-tool-types
   "value-types"     list-value-types})

(defn- list-all
  "Lists all of the element types that are available to the listing service."
  [params]
  (reduce merge {} (map #(% params) (vals listing-fns))))

(defn list-elements "Lists selected workflow elements.  This function handles requests to list
   various different types of workflow elements."
  [elm-type params]
  (cond
    (= elm-type "all")               (list-all params)
    (contains? listing-fns elm-type) ((listing-fns elm-type) params)
    :else (throw+ {:type ::unrecognized_workflow_component_type
                   :name elm-type})))
