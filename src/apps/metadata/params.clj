(ns apps.metadata.params
  (:require [apps.metadata.reference-genomes :only [get-reference-genomes-by-id]]
            [apps.persistence.app-metadata :as persistence]
            [apps.util.conversions :as conv]
            [kameleon.uuids :refer [uuidify]]
            [korma.core :as sql]))

(defn- reference-param?
  [param-type]
  (contains? persistence/param-reference-genome-types param-type))

(defn- selection-param?
  [param-type]
  (re-find #"Selection$" param-type))

(defn- tree-selection-param?
  [param-type]
  (= "TreeSelection" param-type))

(defn- format-tree-root
  [root]
  (conv/remove-nil-vals
   {:id               (:id root)
    :selectionCascade (:name root)
    :isSingleSelect   (:isDefault root)}))

(defn- format-param-value
  [param-value]
  (and param-value (conv/remove-nil-vals (dissoc param-value :parent_id))))

(defn- format-tree-node
  [values-map node children]
  (if (seq children)
    (let [is-group? (comp values-map :id)]
      (assoc node
             :groups    (filter is-group? children)
             :arguments (remove is-group? children)))
    node))

(defn- format-tree-values
  ([param-values]
   (let [values-map (group-by :parent_id param-values)
         root       (format-tree-root (first (values-map nil)))]
     (format-tree-values values-map root)))
  ([values-map {:keys [id] :as node}]
   (->> (values-map id)
        (mapv (comp format-param-value (partial format-tree-values values-map)))
        (format-tree-node values-map node))))

(defn format-param-values
  [type param-values]
  (cond (tree-selection-param? type) [(format-tree-values param-values)]
        (selection-param? type)      (mapv format-param-value param-values)
        :else                        []))

(defn format-reference-genome-value
  [uuid]
  (when uuid (-> uuid
                 uuidify
                 get-reference-genomes-by-id
                 first
                 conv/remove-nil-vals)))

(defn get-param-values
  [id]
  (-> (sql/select* :parameter_values)
      (sql/fields :id :parent_id :name :value [:label :display] :description [:is_default :isDefault])
      (sql/where {:parameter_id id})
      (sql/order [:parent_id :display_order])
      (sql/select)))

(defn params-base-query
  []
  (-> (sql/select* [:task_param_listing :p])
      (sql/fields [:p.description   :description]
                  [:p.id            :id]
                  [:p.name          :name]
                  [:p.label         :label]
                  [:p.is_visible    :is_visible]
                  [:p.required      :required]
                  [:p.omit_if_blank :omit_if_blank]
                  [:p.ordering      :order]
                  [:parameter_type  :type]
                  :retain
                  :is_implicit
                  :repeat_option_flag
                  :info_type
                  :data_format
                  :data_source)))

(defn get-default-value
  [type param-values]
  (let [default (first (filter :isDefault param-values))]
    (cond
      (tree-selection-param? type) nil
      (selection-param? type)      (format-param-value default)
      (reference-param? type)      (format-reference-genome-value (:value default))
      :else                        (:value default))))

(defn- format-validator
  [{type-id :type_id :keys [id type]}]
  {:type   type
   :params (mapv conv/convert-rule-argument
                 (map :argument_value (persistence/get-rule-arguments id))
                 (map :argument_type (persistence/get-rule-arg-definitions type-id)))})

(defn get-validators
  [param-id]
  (mapv format-validator
        (sql/select [:validation_rules :r]
                    (sql/join [:rule_type :t] {:r.rule_type :t.id})
                    (sql/fields :r.id [:t.name :type] [:t.id :type_id])
                    (sql/where {:r.parameter_id param-id
                                :t.deprecated   false}))))

(defn- add-default-value
  [{:keys [id type] :as param}]
  (assoc param :default_value (get-default-value type (get-param-values id))))

(defn load-app-params
  [app-version-id]
  (->> (sql/select (params-base-query)
                   (sql/join :inner [:app_steps :s] {:p.task_id :s.task_id})
                   (sql/join :inner [:app_versions :v] {:s.app_version_id :v.id})
                   (sql/fields [:s.id :step_id])
                   (sql/where {:v.id app-version-id}))
       (mapv add-default-value)))

(defn load-hidden-params
  [app-version-id]
  (mapv add-default-value
        (-> (params-base-query)
            (sql/join :inner [:app_steps :s] {:p.task_id :s.task_id})
            (sql/where {:s.app_version_id app-version-id
                        :p.is_visible     false})
            sql/select)))
