(ns apps.service.apps.de.jobs.common
  (:use [apps.persistence.users :only [get-user-id]]
        [apps.util.assertions :only [assert-not-nil]]
        [apps.util.conversions :only [remove-nil-vals]]
        [kameleon.uuids :only [uuid]]
        [korma.core :exclude [update]]
        [medley.core :only [remove-vals]])
  (:require [apps.clients.iplant-groups :as ipg]
            [apps.containers :as c]
            [apps.tools :as t]
            [apps.service.apps.de.jobs.params :as params]
            [apps.service.apps.jobs.util :as util]
            [clojure.string :as string]))

(defn- format-io-map
  [mapping]
  [(util/qual-id (:target_step mapping) (:input mapping))
   (util/qual-id (:source_step mapping) (:output mapping))])

(defn load-io-maps
  [app-version-id]
  (->> (select [:workflow_io_maps :wim]
               (join [:input_output_mapping :iom] {:wim.id :iom.mapping_id})
               (fields :wim.source_step :iom.output :wim.target_step :iom.input)
               (where {:wim.app_version_id  app-version-id
                       :iom.external_input  nil
                       :iom.external_output nil}))
       (map format-io-map)
       (into {})))

(defn build-default-values-map
  [params]
  (remove-vals (comp string/blank? str)
               (into {} (map (juxt util/param->qual-id :default_value) params))))

(defn build-config
  [inputs outputs params]
  {:input  inputs
   :output outputs
   :params params})

(defn- build-environment-entries
  [config default-values param]
  (let [value (params/value-for-param config default-values param)]
    (if (or (util/not-blank? value) (not (:omit_if_blank param)))
      [[(:name param) value]]
      [])))

(defn build-environment
  [config default-values params]
  (->> (filter #(= (:type %) util/environment-variable-type) params)
       (mapcat (partial build-environment-entries config default-values))
       (into {})))

(defn- filter-min-requirement-keys
  [m]
  (select-keys m [:min_memory_limit
                  :min_cpu_cores
                  :min_disk_space]))

(defn- filter-max-requirement-keys
  [m]
  (select-keys m [:memory_limit
                  :max_cpu_cores]))

(defn- limit-container-min-requirement
  [container req-key-min req-key-max]
  (if (and (contains? container req-key-min)
           (contains? container req-key-max))
    (assoc container req-key-min (min (get container req-key-min)
                                      (get container req-key-max)))
    container))

(defn- reconcile-container-requirements
  "reconcile submission requirement requests with tool requirements"
  [container requirements]
  (-> (merge container
             (merge-with max
                         (filter-min-requirement-keys container)
                         (filter-min-requirement-keys requirements))
             (merge-with min
                         (filter-max-requirement-keys container)
                         (filter-max-requirement-keys requirements)))
      (limit-container-min-requirement :min_memory_limit :memory_limit)
      (limit-container-min-requirement :min_cpu_cores :max_cpu_cores)))

(defn- add-container-info
  [{tool-id :id tool-type :type :as component} requirements]
  (dissoc
   (if (c/tool-has-settings? tool-id)
     (assoc component :container (-> tool-id
                                     (c/tool-container-info :auth? true)
                                     (t/format-container-settings (not= tool-type "executable"))
                                     (reconcile-container-requirements requirements)))
     component)
   :id))

(defn- load-step-component
  [task-id requirements]
  (-> (select* :tasks)
      (join :tools {:tasks.tool_id :tools.id})
      (join :tool_types {:tools.tool_type_id :tool_types.id})
      (fields :tools.description
              :tools.location
              :tools.name
              [:tool_types.name :type]
              :tools.id
              :tools.restricted
              :tools.interactive
              :tools.time_limit_seconds)
      (where {:tasks.id task-id})
      (select)
      (first)
      (add-container-info requirements)
      (remove-nil-vals)))

(defn build-component
  [{task-id :task_id} requirements]
  (assert-not-nil [:tool-for-task task-id] (load-step-component task-id requirements)))

(defn build-step
  [request-builder requirements steps step]
  (let [config  (.buildConfig request-builder steps step)
        stdout  (:stdout config)
        stderr  (:stderr config)
        step_requirements (first (filter #(= (:step_number step) (:step_number %)) requirements))]
    (conj steps
          (remove-nil-vals
           {:component   (.buildComponent request-builder step step_requirements)
            :environment (.buildEnvironment request-builder step)
            :config      (dissoc config :stdout :stderr)
            :stdout      stdout
            :stderr      stderr
            :type        "condor"}))))

(defn- load-steps
  [app-version-id]
  (select [:app_steps :s]
          (join [:tasks :t] {:s.task_id :t.id})
          (fields [:s.id              :id]
                  [:s.step            :step_number]
                  [:s.task_id         :task_id]
                  [:t.external_app_id :external_app_id])
          (where {:s.app_version_id app-version-id})
          (order :s.step)))

(defn build-steps
  [request-builder app submission]
  (->> (load-steps (:version_id app))
       (drop (dec (:starting_step submission 1)))
       (take-while (comp nil? :external_app_id))
       (reduce #(.buildStep request-builder (:requirements submission) %1 %2) [])))

(defn- load-htcondor-extra-requirements
  [app-version-id]
  (first
   (select :apps_htcondor_extra
           (fields [:extra_requirements])
           (where {:app_version_id app-version-id}))))

(defn build-extra
  [request-builder app]
  {:htcondor (load-htcondor-extra-requirements (:version_id app))})

(defn- interactive?
  "Returns true if the given submission is for an interactive job."
  [job]
  (some true? (map #(get-in %1 [:component :interactive]) (:steps job))))

(defn- execution-target
  "Returns the execution-target value based on info in the submission."
  [job]
  (assoc job :execution_target (cond
                                 (interactive? job) "interapps"
                                 :else "condor")))

(defn build-submission
  [request-builder user email submission app]
  (let [groups (future (:groups (ipg/lookup-subject-groups (:shortUsername user))))
        steps  (future (.buildSteps request-builder))
        extra  (future (.buildExtra request-builder))]
    (-> {:app_description      (:description app)
         :app_id               (:id app)
         :app_version_id       (:version_id app)
         :app_name             (:name app)
         :archive_logs         (:archive_logs submission)
         :callback             (:callback submission)
         :create_output_subdir (:create_output_subdir submission true)
         :description          (:description submission "")
         :email                email
         :group                (:group submission "")
         :name                 (:name submission)
         :notify               (:notify submission)
         :notify_periodic      (:notify_periodic submission nil)
         :periodic_period      (:periodic_period submission nil)
         :output_dir           (:output_dir submission)
         :request_type         "submit"
         :steps                @steps
         :extra                @extra
         :username             (:shortUsername user)
         :user_id              (get-user-id (:username user))
         :user_groups          (map (comp ipg/remove-environment-from-group :name) @groups)
         :user_home            (:user_home submission)
         :uuid                 (or (:uuid submission) (uuid))
         :wiki_url             (:wiki_url app)
         :skip-parent-meta     (:skip-parent-meta submission)
         :file-metadata        (:file-metadata submission)}
        execution-target)))
