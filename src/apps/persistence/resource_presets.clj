(ns apps.persistence.resource-presets
  "Persistence layer for resource presets."
  (:require [apps.util.assertions :refer [assert-not-nil]]
            [apps.util.db :as db]
            [clojure.java.jdbc :as jdbc]
            [honey.sql :as sql]
            [honey.sql.helpers :as h])
  (:import [java.util UUID]))

(defn- base-preset-query
  "Builds the base SELECT query for resource presets."
  []
  (-> (h/select :id :label :description :max_cpu_cores :min_memory_limit
                :max_gpus :time_limit_seconds :display_order :is_default :is_enabled)
      (h/from :resource_presets)))

(defn get-resource-presets
  "Lists all enabled resource presets, ordered by display_order."
  []
  (db/with-transaction [tx]
    (jdbc/query tx (-> (base-preset-query)
                       (h/where [:= :is_enabled true])
                       (h/order-by [:display_order :asc])
                       sql/format))))

(defn get-all-resource-presets
  "Lists all resource presets including disabled ones, ordered by display_order.
   Used by admin endpoints."
  []
  (db/with-transaction [tx]
    (jdbc/query tx (-> (base-preset-query)
                       (h/order-by [:display_order :asc])
                       sql/format))))

(defn get-resource-preset-by-id
  "Gets a single resource preset by its ID. Returns nil if not found."
  [preset-id]
  (db/with-transaction [tx]
    (first (jdbc/query tx (-> (base-preset-query)
                              (h/where [:= :id (UUID/fromString (str preset-id))])
                              sql/format)))))

(defn get-valid-resource-preset
  "Gets a resource preset by ID, throwing :not-found if it does not exist."
  [preset-id]
  (assert-not-nil [:preset-id preset-id]
                  (get-resource-preset-by-id preset-id)))

(defn create-resource-preset
  "Creates a new resource preset and returns it."
  [{:keys [label description max_cpu_cores min_memory_limit max_gpus
           time_limit_seconds display_order is_default is_enabled]}]
  (db/with-transaction [tx]
    (when is_default
      (jdbc/execute! tx (-> (h/update :resource_presets)
                            (h/set {:is_default false})
                            sql/format)))
    (let [id (UUID/randomUUID)]
      (jdbc/execute! tx (-> (h/insert-into :resource_presets)
                            (h/values [{:id                 id
                                        :label              label
                                        :description        description
                                        :max_cpu_cores      max_cpu_cores
                                        :min_memory_limit   min_memory_limit
                                        :max_gpus           (or max_gpus 0)
                                        :time_limit_seconds time_limit_seconds
                                        :display_order      (or display_order 0)
                                        :is_default         (boolean is_default)
                                        :is_enabled         (if (nil? is_enabled) true is_enabled)}])
                            sql/format))
      (get-resource-preset-by-id id))))

(def ^:private updatable-fields
  "Fields that can be updated via PATCH. These are the keys we accept from the client."
  [:label :description :max_cpu_cores :min_memory_limit :max_gpus
   :time_limit_seconds :display_order :is_default :is_enabled])

(defn update-resource-preset
  "Updates an existing resource preset and returns the updated version.
   Only keys present in the updates map are written; explicitly provided nil
   values clear nullable columns (description, time_limit_seconds)."
  [preset-id updates]
  (get-valid-resource-preset preset-id)
  (db/with-transaction [tx]
    (when (:is_default updates)
      (jdbc/execute! tx (-> (h/update :resource_presets)
                            (h/set {:is_default false})
                            sql/format)))
    (let [;; select-keys returns only keys present in the updates map,
          ;; preserving nil values for nullable columns (to allow clearing).
          set-map (select-keys updates updatable-fields)]
      (when (seq set-map)
        (jdbc/execute! tx (-> (h/update :resource_presets)
                              (h/set set-map)
                              (h/where [:= :id (UUID/fromString (str preset-id))])
                              sql/format))))
    (get-resource-preset-by-id preset-id)))

(defn delete-resource-preset
  "Deletes a resource preset by its ID. Throws :not-found if the preset does not exist."
  [preset-id]
  (get-valid-resource-preset preset-id)
  (db/with-transaction [tx]
    (jdbc/execute! tx (-> (h/delete-from :resource_presets)
                          (h/where [:= :id (UUID/fromString (str preset-id))])
                          sql/format)))
  nil)

(defn set-default-resource-preset
  "Sets the given preset as the global default (clearing any previous default).
   Throws :not-found if the preset does not exist."
  [preset-id]
  (get-valid-resource-preset preset-id)
  (db/with-transaction [tx]
    (jdbc/execute! tx (-> (h/update :resource_presets)
                          (h/set {:is_default false})
                          sql/format))
    (jdbc/execute! tx (-> (h/update :resource_presets)
                          (h/set {:is_default true})
                          (h/where [:= :id (UUID/fromString (str preset-id))])
                          sql/format))
    (get-resource-preset-by-id preset-id)))
