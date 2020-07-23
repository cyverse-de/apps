(ns apps.service.apps.de.listings
  (:use [apps.constants :only [de-system-id executable-tool-type]]
        [apps.persistence.app-documentation :only [get-documentation]]
        [apps.persistence.app-groups]
        [apps.persistence.app-listing]
        [apps.persistence.entities]
        [apps.persistence.tools :only [get-tools-by-id]]
        [apps.util.assertions :only [assert-not-nil]]
        [apps.util.config]
        [apps.util.conversions :only [to-long remove-nil-vals]]
        [apps.workspace]
        [kameleon.uuids :only [uuidify]]
        [korma.core :exclude [update]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.metadata :as metadata-client]
            [apps.clients.permissions :as perms-client]
            [apps.persistence.app-metadata :refer [get-app get-app-tools] :as amp]
            [apps.persistence.jobs :as jobs-db]
            [apps.service.apps.de.constants :as c]
            [apps.service.apps.de.permissions :as perms]
            [apps.service.apps.de.docs :as docs]
            [apps.service.util :as svc-util]
            [apps.tools :as tools]
            [apps.tools.permissions :as tool-perms]
            [cemerick.url :as curl]
            [clojure.set :as set]
            [clojure.string :as string]))

(def my-public-apps-id (uuidify "00000000-0000-0000-0000-000000000000"))
(def shared-with-me-id (uuidify "EEEEEEEE-EEEE-EEEE-EEEE-EEEEEEEEEEEE"))
(def trash-category-id (uuidify "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"))

(def default-sort-params
  {:sort-field :lower_case_name
   :sort-dir   :ASC})

(defn- augment-listing-params
  ([params short-username perms]
   (assoc params
          :app-ids        (set (keys perms))
          :public-app-ids (perms-client/get-public-app-ids)))
  ([params short-username]
   (augment-listing-params params short-username (perms-client/load-app-permissions short-username))))

(defn- app-search-candidates
  [admin? public-app-ids accessible-app-ids app-subset]
  (if admin?
    (case app-subset
      :all     (get-all-app-ids)
      :private (set/difference (get-all-app-ids) public-app-ids)
      public-app-ids)
    accessible-app-ids))

(defn- admin-search-app-ids
  [public-app-ids])

(defn- augment-search-params
  [search_term {:keys [app-ids public-app-ids app-subset] :as params} short-username admin?]
  (let [category-attrs (set (workspace-metadata-category-attrs))
        app-ids        (app-search-candidates admin? public-app-ids app-ids app-subset)]
    (-> params
        (assoc :app-ids             app-ids
               :pre-matched-app-ids (when-not (some empty? [search_term app-ids category-attrs])
                                      (metadata-client/filter-targets-by-ontology-search
                                       short-username
                                       category-attrs
                                       search_term
                                       app-ids
                                       :validate false)))
        remove-nil-vals)))

(defn list-hierarchies
  [{:keys [username]}]
  (metadata-client/list-hierarchies username))

(defn- fix-sort-params
  [params]
  (let [params (merge default-sort-params params)]
    (if (= (keyword (:sort-field params)) :name)
      (assoc params :sort-field (:sort-field default-sort-params))
      params)))

(defn- add-subgroups
  [{:keys [app_count] :as group} groups]
  (let [group     (assoc group :system_id de-system-id)
        subgroups (filter #(= (:id group) (:parent_id %)) groups)
        subgroups (map #(add-subgroups % groups) subgroups)
        result    (if (empty? subgroups) group (assoc group :categories subgroups))]
    (-> result
        (assoc :total app_count)
        (dissoc :app_count :parent_id :workspace_id :description))))

(defn format-trash-category
  "Formats the virtual group for the admin's deleted and orphaned apps category."
  [_ _ params]
  {:system_id  de-system-id
   :id         trash-category-id
   :name       "Trash"
   :is_public  true
   :total      (delay (count-deleted-and-orphaned-apps params))})

(defn list-trashed-apps
  "Lists the public, deleted apps and orphaned apps."
  [_ _ params]
  (list-deleted-and-orphaned-apps params))

(defn- format-my-public-apps-group
  "Formats the virtual group for the user's public apps."
  [{:keys [username]} _ params]
  {:system_id de-system-id
   :id        my-public-apps-id
   :name      "My public apps"
   :is_public false
   :total     (future (count-public-apps-by-user username params))})

(defn list-my-public-apps
  "Lists the public apps belonging to the user with the given workspace."
  [{:keys [username]} workspace params]
  (list-public-apps-by-user
   workspace
   (workspace-favorites-app-category-index)
   username
   params))

(defn format-shared-with-me-category
  "Formats the virtual group for apps that have been shared with the user."
  [_ workspace params]
  {:system_id de-system-id
   :id        shared-with-me-id
   :name      "Shared with me"
   :is_public false
   :total     (future (count-shared-apps workspace (workspace-favorites-app-category-index) params))})

(defn list-apps-shared-with-me
  [_ workspace params]
  (list-shared-apps workspace (workspace-favorites-app-category-index) params))

(def ^:private virtual-group-fns
  {my-public-apps-id {:format-group   format-my-public-apps-group
                      :format-listing list-my-public-apps}
   trash-category-id {:format-group   format-trash-category
                      :format-listing list-trashed-apps}
   shared-with-me-id {:format-group   format-shared-with-me-category
                      :format-listing list-apps-shared-with-me}})

(def ^:private virtual-group-ids (set (keys virtual-group-fns)))

(defn- realize-group
  [group]
  (reduce (fn [group k] (if (or (future? (group k)) (delay? (group k))) (update group k deref) group)) group (keys group)))

(defn- format-private-virtual-groups
  "Formats any virtual groups that should appear in a user's workspace."
  [user workspace params]
  (map realize-group
    (doall (remove :is_public ;; resolve immediately to start futures executing
      (map (fn [[_ {f :format-group}]] (f user workspace params)) virtual-group-fns)))))

(defn- add-private-virtual-groups
  [user group workspace params]
  (let [virtual-groups (format-private-virtual-groups user workspace params)
        actual-count   (future (count-apps-in-group-for-user
                                 (:id group)
                                 (:username user)
                                 params))]
    (-> group
        (update-in [:categories] concat virtual-groups)
        (assoc :total actual-count))))

(defn- format-app-group-hierarchy
  "Formats the app group hierarchy rooted at the app group with the given
   identifier."
  [user user-workspace params {root-id :root_category_id workspace-id :id}]
  (let [groups (get-app-group-hierarchy root-id params)
        root   (first (filter #(= root-id (:id %)) groups))
        result (add-subgroups root groups)]
    (if (= (:id user-workspace) workspace-id)
      (add-private-virtual-groups user result user-workspace params)
      result)))

(defn- get-workspace-app-groups
  "Retrieves the list of the current user's workspace app groups."
  [user params]
  (let [workspace (get-workspace (:username user))]
    [(format-app-group-hierarchy user workspace params workspace)]))

(defn- get-visible-app-groups-for-workspace
  "Retrieves the list of app groups that are visible from a workspace."
  [user-workspace user params]
  (let [workspaces (get-visible-workspaces (:id user-workspace))]
    (map (partial format-app-group-hierarchy user user-workspace params) workspaces)))

(defn- get-visible-app-groups
  "Retrieves the list of app groups that are visible to a user."
  [user {:keys [admin] :as params}]
  (-> (when-not admin (get-optional-workspace (:username user)))
      (get-visible-app-groups-for-workspace user params)))

(defn get-app-groups
  "Retrieves the list of app groups that are visible to all users, the current user's app groups, or
   both, depending on the :public param."
  [user {:keys [public] :as params}]
  (let [params (augment-listing-params params (:shortUsername user))]
    (if (contains? params :public)
      (if-not public
        (get-workspace-app-groups user params)
        (get-visible-app-groups-for-workspace nil user params))
      (get-visible-app-groups user params))))

(defn get-app-hierarchy
  ([user root-iri attr]
   (get-app-hierarchy user (metadata-client/get-active-hierarchy-version) root-iri attr))
  ([{:keys [username shortUsername]} ontology-version root-iri attr]
   (let [app-ids         (set (keys (perms-client/load-app-permissions shortUsername)))
         visible-app-ids (amp/filter-visible-app-ids app-ids)]
     (metadata-client/filter-hierarchy username ontology-version root-iri attr visible-app-ids))))

(defn get-admin-app-groups
  "Retrieves the list of app groups that are accessible to administrators. This includes all public
   app groups along with the trash group."
  [user params]
  (let [params (assoc (augment-listing-params params (:shortUsername user)) :admin true :public true)]
    (conj (vec (get-app-groups user params))
          (format-trash-category nil nil params))))

(defn- format-app-group-info
  "Formats app category information for the admin app category search."
  [category]
  (assoc (select-keys category [:id :name :owner])
         :system_id de-system-id))

(defn search-admin-app-groups
  "Searches for admin app categories by name."
  [{names :name}]
  (mapv format-app-group-info (search-app-groups names)))

(defn- validate-app-pipeline-eligibility
  "Validates an App for pipeline eligibility, throwing a slingshot stone ."
  [app]
  (let [app_id (:id app)
        step_count (:step_count app)
        overall_job_type (:overall_job_type app)]
    (if (< step_count 1)
      (throw+ {:reason
               (str "Analysis, "
                    app_id
                    ", has too few steps for a pipeline.")}))
    (if (> step_count 1)
      (throw+ {:reason
               (str "Analysis, "
                    app_id
                    ", has too many steps for a pipeline.")}))
    (if-not (= overall_job_type executable-tool-type)
      (throw+ {:reason
               (str "Job type, "
                    overall_job_type
                    ", can't currently be included in a pipeline.")}))))

(defn- format-app-pipeline-eligibility
  "Validates an App for pipeline eligibility, reformatting its :overall_job_type value, and
   replacing it with a :pipeline_eligibility map"
  [app]
  (let [pipeline_eligibility (try+
                              (validate-app-pipeline-eligibility app)
                              {:is_valid true
                               :reason ""}
                              (catch map? {:keys [reason]}
                                {:is_valid false
                                 :reason reason}))
        app (dissoc app :overall_job_type)]
    (assoc app :pipeline_eligibility pipeline_eligibility)))

(defn- format-app-ratings
  "Formats an App's :average_rating, :user_rating, and :comment_id values into a
   :rating map."
  [{:keys [average_rating total_ratings user_rating comment_id] :as app}]
  (-> app
      (dissoc :average_rating :total_ratings :user_rating :comment_id)
      (assoc :rating (remove-nil-vals
                      {:average average_rating
                       :total total_ratings
                       :user user_rating
                       :comment_id comment_id}))))

(defn- app-can-run?
  [{tool-count :tool_count external-app-count :external_app_count task-count :task_count}]
  (= (+ tool-count external-app-count) task-count))

(defn- format-app-permissions
  "Formats the permission setting in the app. There are some cases where admins can view apps
  for which they don't have permissions. For example, when viewing orphaned and deleted apps.
  For the time being we'll deal with that by defaulting the permission level to the empty string,
  indicating that the user has no explicit permissions on the app."
  [app perms]
  (assoc app :permission (or (perms (:id app)) "")))

(defn- format-app-listing-job-stats
  [app admin?]
  (if admin?
    (svc-util/format-job-stats app admin?)
    app))

(defn- format-app-listing
  "Formats certain app fields into types more suitable for the client."
  [admin? perms beta-ids-set public-app-ids {:keys [id] :as app}]
  (-> (assoc app :can_run (app-can-run? app))
      (dissoc :tool_count :task_count :external_app_count :lower_case_name)
      (format-app-listing-job-stats admin?)
      (format-app-ratings)
      (format-app-pipeline-eligibility)
      (format-app-permissions perms)
      (assoc :can_favor true :can_rate true :app_type "DE" :system_id c/system-id)
      (assoc :beta (contains? beta-ids-set id))
      (assoc :is_public (contains? public-app-ids id))
      (remove-nil-vals)))

(defn- app-ids->beta-ids-set
  "Filters the given list of app-ids into a set containing the ids of apps marked as `beta`"
  [username app-ids]
  (let [beta-avu {:attr (workspace-metadata-beta-attr-iri)
                  :value (workspace-metadata-beta-value)}]
    (set (metadata-client/filter-by-avus username app-ids [beta-avu]))))

(defn- filter-app-ids-by-community
  "Filters the given list of app-ids into a set containing the ids of apps tagged with the given community-id"
  [username community-id app-ids]
  (let [community-avu {:attr  (workspace-metadata-communities-attr)
                       :value community-id}]
    (set (metadata-client/filter-by-avus username app-ids [community-avu]))))

(defn- app-listing-by-id
  [{:keys [username shortUsername]} params perms app-ids admin?]
  (let [workspace      (get-optional-workspace username)
        faves-index    (workspace-favorites-app-category-index)
        beta-ids-set   (app-ids->beta-ids-set shortUsername app-ids)
        public-app-ids (perms-client/get-public-app-ids)
        count-apps-fn  (if admin? count-apps-for-admin count-apps-for-user)
        total          (if (empty? app-ids) 0 (count-apps-fn nil (:id workspace) (assoc params :app-ids app-ids)))
        app-listing-fn (if admin? admin-list-apps-by-id list-apps-by-id)
        app-listing    (app-listing-fn workspace faves-index app-ids (fix-sort-params params))]
    {:total total
     :apps  (map (partial format-app-listing admin? perms beta-ids-set public-app-ids) app-listing)}))

(defn list-apps-by-tool
  "Lists all apps accessible to the user that use the given tool."
  [{:keys [shortUsername] :as user} tool-id params admin?]
  (let [perms   (perms-client/load-app-permissions shortUsername)
        app-ids (-> tool-id
                    amp/get-app-ids-by-tool-id
                    set
                    (clojure.set/intersection (set (keys perms))))]
    (app-listing-by-id user params perms app-ids admin?)))

(defn user-list-apps-by-tool
  "Lists all apps accessible to the user that use the given tool, if readable by the user."
  [{:keys [shortUsername] :as user} tool-id params]
  (tool-perms/check-tool-permissions shortUsername "read" [tool-id])
  (list-apps-by-tool user tool-id params false))

(defn- apps-listing-with-metadata-filter
  [{:keys [shortUsername] :as user} params metadata-filter admin?]
  (let [perms           (perms-client/load-app-permissions shortUsername)
        app-ids         (set (keys perms))
        app-listing-ids (metadata-filter app-ids)]
    (app-listing-by-id user params perms app-listing-ids admin?)))

(defn list-apps-under-hierarchy
  ([user root-iri attr params]
   (list-apps-under-hierarchy user (metadata-client/get-active-hierarchy-version) root-iri attr params false))
  ([{:keys [username] :as user} ontology-version root-iri attr params admin?]
   (let [metadata-filter (partial metadata-client/filter-hierarchy-targets username ontology-version root-iri attr)]
     (apps-listing-with-metadata-filter user params metadata-filter admin?))))

(defn get-unclassified-app-listing
  ([user root-iri attr params]
   (get-unclassified-app-listing user (metadata-client/get-active-hierarchy-version) root-iri attr params false))
  ([{:keys [username] :as user} ontology-version root-iri attr params admin?]
   (let [metadata-filter (partial metadata-client/filter-unclassified username ontology-version root-iri attr)]
     (apps-listing-with-metadata-filter user params metadata-filter admin?))))

(defn list-apps-in-community
  [{:keys [username] :as user} community-id params admin?]
  (let [metadata-filter (partial filter-app-ids-by-community username community-id)]
    (apps-listing-with-metadata-filter user params metadata-filter admin?)))

(defn- list-apps-in-virtual-group
  "Formats a listing for a virtual group."
  [{:keys [shortUsername] :as user} workspace group-id perms {:keys [public-app-ids] :as params}]
  (when-let [format-fns (virtual-group-fns group-id)]
    (-> ((:format-group format-fns) user workspace params)
        (assoc :apps (let [app-listing  ((:format-listing format-fns) user workspace params)
                           beta-ids-set (app-ids->beta-ids-set shortUsername (map :id app-listing))]
                       (map (partial format-app-listing false perms beta-ids-set public-app-ids) app-listing)))
        (realize-group))))

(defn- count-apps-in-group
  "Counts the number of apps in an app group, including virtual app groups that may be included."
  [user {root-group-id :root_category_id} {:keys [id] :as app-group} params]
  (if (= root-group-id id)
    (count-apps-in-group-for-user id (:username user) params)
    (count-apps-in-group-for-user id params)))

(defn- get-apps-in-group
  "Gets the apps in an app group, including virtual app groups that may be included."
  [user {root-group-id :root_category_id :as workspace} {:keys [id]} params]
  (let [faves-index (workspace-favorites-app-category-index)]
    (if (= root-group-id id)
      (get-apps-in-group-for-user id workspace faves-index params (:username user))
      (get-apps-in-group-for-user id workspace faves-index params))))

(defn- list-apps-in-real-group
  "This service lists all of the apps in a real app group and all of its descendents."
  [{:keys [shortUsername] :as user} workspace category-id perms {:keys [public-app-ids] :as params}]
  (let [app_group      (->> (get-app-category category-id)
                            (assert-not-nil ["category_id" category-id])
                            remove-nil-vals)
        total          (count-apps-in-group user workspace app_group params)
        apps_in_group  (get-apps-in-group user workspace app_group params)
        beta-ids-set   (app-ids->beta-ids-set shortUsername (map :id apps_in_group))
        apps_in_group  (map (partial format-app-listing false perms beta-ids-set public-app-ids) apps_in_group)]
    (assoc app_group
           :system_id de-system-id
           :total     total
           :apps      apps_in_group)))

(defn list-apps-in-group
  "This service lists all of the apps in an app group and all of its
   descendents."
  [user app-group-id params]
  (let [workspace (get-optional-workspace (:username user))
        perms     (perms-client/load-app-permissions (:shortUsername user))
        params    (fix-sort-params (augment-listing-params params (:shortUsername user) perms))]
    (or (list-apps-in-virtual-group user workspace app-group-id perms params)
        (list-apps-in-real-group user workspace app-group-id perms params))))

(defn has-category
  "Determines whether or not a category with the given ID exists."
  [category-id]
  (or (virtual-group-ids category-id)
      (seq (select :app_categories (where {:id category-id})))))

(defn list-apps
  "This service fetches a paged list of apps in the user's workspace and all public app groups,
   further filtering results by a search term if the `search` parameter is present.

   Note: the :new parameter is intended to be used for debugging. I'll remove it when the performance
   improvements are done. In the meantime, I want to be able to easily switch back to the old search
   queries in case any bugs are identified."
  [{:keys [username shortUsername]} params admin?]
  (let [search_term    (curl/url-decode (:search params))
        workspace      (get-workspace username)
        perms          (perms-client/load-app-permissions shortUsername)
        params         (-> params
                           (augment-listing-params shortUsername perms)
                           (assoc :orphans admin?)
                           fix-sort-params)
        params         (augment-search-params search_term params shortUsername admin?)
        count-apps-fn  (if (:new params true)
                         (if admin? new-count-apps-for-admin new-count-apps-for-user)
                         (if admin? count-apps-for-admin count-apps-for-user))
        total          (count-apps-fn search_term (:id workspace) params)
        app-listing-fn (if (:new params true)
                         (if admin? new-get-apps-for-admin new-get-apps-for-user)
                         (if admin? get-apps-for-admin get-apps-for-user))
        apps           (app-listing-fn search_term
                                       workspace
                                       (workspace-favorites-app-category-index)
                                       params)
        beta-ids-set   (app-ids->beta-ids-set shortUsername (map :id apps))
        public-app-ids (:public-app-ids params)
        apps           (map (partial format-app-listing admin? perms beta-ids-set public-app-ids) apps)]
    {:total total
     :apps  apps}))

(defn list-app
  "This service retrieves app listing information for a single app."
  [{:keys [username shortUsername]} app-id]
  (perms/check-app-permissions shortUsername "read" [app-id])
  (let [workspace      (get-workspace username)
        perms          (perms-client/load-app-permissions shortUsername)
        total          (count-apps-for-user nil (:id workspace) {:app-ids [app-id]})
        apps           (get-single-app workspace (workspace-favorites-app-category-index) app-id)
        beta-ids-set   (app-ids->beta-ids-set shortUsername [app-id])
        public-app-ids (perms-client/get-public-app-ids)
        apps           (map (partial format-app-listing false perms beta-ids-set public-app-ids) apps)]
    {:total total
     :apps  apps}))

(defn- load-app-details
  "Retrieves the details for a single app."
  [app-id]
  (assert-not-nil [:app-id app-id]
                  (first (select apps
                                 (with app_references)
                                 (with integration_data)
                                 (where {:id app-id})))))

(defn- format-wiki-url
  "CORE-6510: Remove the wiki_url from app details responses if the App has documentation saved."
  [{:keys [id wiki_url] :as app}]
  (assoc app :wiki_url (if (get-documentation id) nil wiki_url)))

(defn- format-app-hierarchies
  [{app-id :id :as app} username]
  (let [ontology-version (metadata-client/get-active-hierarchy-version :validate false)
        hierarchies      (if ontology-version
                           (metadata-client/filter-hierarchies username
                                                               ontology-version
                                                               (workspace-metadata-category-attrs)
                                                               app-id)
                           {:hierarchies []})]
    (merge app hierarchies)))

(defn- format-app-details-job-stats
  [^String app-id params admin?]
  (remove-nil-vals
   (if admin? (jobs-db/get-job-stats app-id params)
       (jobs-db/get-public-job-stats app-id params))))

(defn- format-app-extra-info
  [app-id admin?]
  (if admin?
    (get-app-extra-info app-id)
    nil))

(defn- format-app-documentation
  [app-id username admin?]
  (when admin?
    (try+
     (docs/get-app-docs username app-id admin?)
     (catch [:type :clojure-commons.exception/not-found] _ nil))))

(defn- format-tool-image [{:keys [image_name image_tag image_url deprecated]}]
  (remove-nil-vals
   {:name       image_name
    :tag        image_tag
    :url        image_url
    :deprecated deprecated}))

(defn- format-app-tool [tool]
  (assoc (remove-nil-vals (select-keys tool [:id :name :description :location :type :version :attribution]))
         :container {:image (format-tool-image tool)}))

(defn- format-app-details
  "Formats information for the get-app-details service."
  [username details tools admin?]
  (let [app-id (:id details)]
    (-> details
        (select-keys [:id :integration_date :edited_date :deleted :disabled :wiki_url
                      :integrator_name :integrator_email])
        (assoc :name                 (:name details "")
               :description          (:description details "")
               :references           (map :reference_text (:app_references details))
               :tools                (map format-app-tool tools)
               :job_stats            (format-app-details-job-stats (str app-id) nil admin?)
               :extra                (format-app-extra-info app-id admin?)
               :documentation        (format-app-documentation app-id username admin?)
               :categories           (get-groups-for-app app-id)
               :suggested_categories (get-suggested-groups-for-app app-id)
               :system_id            c/system-id)
        (format-app-hierarchies username)
        format-wiki-url)))

;; This function was split from `get-app-details` to provide a way for administrative endopints to skip permission
;; checks without including the app stat information.
(defn- get-app-details*
  [username app-id admin?]
  (let [details (load-app-details app-id)
        tools   (get-app-tools app-id)]
    (->> (format-app-details username details tools admin?)
         (remove-nil-vals))))

;; FIXME: remove the code to bypass the permission checks for admin users when we have a better
;; way to implement this.
(defn get-app-details
  "This service obtains the high-level details of an app."
  [{username :shortUsername} app-id admin?]
  (when-not admin?
    (perms/check-app-permissions username "read" [app-id]))
  (get-app-details* username app-id admin?))

(defn- with-task-params
  "Includes a list of related file parameters in the query's result set,
   with fields required by the client."
  [query task-param-entity]
  (with query task-param-entity
        (join :parameter_values {:parameter_values.parameter_id :id})
        (fields :id
                :name
                :label
                :description
                :required
                :parameter_values.value
                [:data_format :format])))

(defn- get-tasks
  "Fetches a list of tasks for the given IDs with their inputs and outputs."
  [task-ids]
  (select tasks
          (join job_types)
          (fields [:job_types.system_id :system_id]
                  [:tasks.id            :id]
                  [:tasks.tool_id       :tool_id]
                  [:tasks.name          :name]
                  [:tasks.description   :description])
          (with-task-params inputs)
          (with-task-params outputs)
          (where (in :tasks.id task-ids))))

(defn- format-task-file-param
  [file-parameter]
  (dissoc file-parameter :value))

(defn- format-task-output
  [{:keys [label value] :as output}]
  (-> output
      (assoc :label (first (remove string/blank? [value label])))
      format-task-file-param))

(defn- format-task
  [user {:keys [tool_id] :as task}]
  (-> task
      (assoc :tool (when tool_id (tools/get-tool user tool_id)))
      (dissoc :tool_id)
      (update-in [:inputs] (partial map (comp remove-nil-vals format-task-file-param)))
      (update-in [:outputs] (partial map (comp remove-nil-vals format-task-output)))
      remove-nil-vals))

(defn get-tasks-with-file-params
  "Fetches a formatted list of tasks for the given IDs with their inputs and outputs."
  [user task-ids]
  (map (partial format-task user) (get-tasks task-ids)))

(defn- format-app-task-listing
  [user {app-id :id :as app}]
  (let [task-ids (map :task_id (select :app_steps (fields :task_id) (where {:app_id app-id})))
        tasks    (get-tasks-with-file-params user task-ids)]
    (-> app
        (select-keys [:id :name :description])
        (assoc :tasks tasks :system_id c/system-id))))

(defn get-app-task-listing
  "A service used to list the file parameters in an app."
  [{username :shortUsername} app-id]
  (perms/check-app-permissions username "read" [app-id])
  (let [app (get-app app-id)]
    (format-app-task-listing username app)))

(defn get-app-tool-listing
  "A service to list the tools used by an app."
  [{username :shortUsername} app-id]
  (perms/check-app-permissions username "read" [app-id])
  (let [app (get-app app-id)
        tasks (:tasks (first (select apps
                                     (with tasks (fields :tool_id))
                                     (where {:apps.id app-id}))))
        tool-ids (map :tool_id tasks)]
    {:tools (get-tools-by-id tool-ids)}))

(defn get-category-id-for-app
  "Determines the category that an app is in. If the category can't be found for the app then
   the app is assumed to be in the 'Shared with me' category. This means that the ID of the
   'Shared with me' category will be returned if the user does not have access to the app. For
   this reason, it is important to verify that the user does, in fact, have access to the app
   when calling this function."
  [{:keys [username]} app-id]
  (or (amp/get-category-id-for-app username app-id (workspace-favorites-app-category-index))
      shared-with-me-id))

(defn get-app-input-ids
  "Gets the list of parameter IDs corresponding to input files."
  [app-id]
  (->> (amp/get-app-parameters app-id)
       (filter (comp amp/param-ds-input-types :type))
       (mapv #(str (:step_id %) "_" (:id %)))))

(defn- format-app-publication-request
  [{username :shortUsername} {app-id :app_id :keys [id requestor]}]
  {:id        id
   :app       (get-app-details* username app-id false)
   :requestor requestor})

(defn list-app-publication-requests
  "Lists app publication requests, optionally filtering the listing by app ID or requestor."
  [user {app-id :app_id include-completed :include_completed :keys [requestor]}]
  (mapv (partial format-app-publication-request user)
        (amp/list-app-publication-requests app-id requestor include-completed)))
