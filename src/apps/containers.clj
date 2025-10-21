(ns apps.containers
  (:require [apps.persistence.app-metadata :refer [get-tools-in-public-apps-by-image-id]]
            [apps.persistence.docker-registries :refer [get-registry-from-image]]
            [apps.persistence.entities :refer [tools
                                               container-images
                                               container-settings
                                               container-devices
                                               container-volumes
                                               container-volumes-from
                                               data-containers
                                               interapps-proxy-settings
                                               ports]]
            [apps.persistence.tools :refer [update-tool]]
            [apps.util.assertions :refer [assert-not-nil]]
            [apps.util.conversions :refer [remove-nil-vals remove-empty-vals]]
            [apps.util.db :refer [transaction]]
            [apps.validation :refer [validate-image-not-used-in-public-apps
                                     validate-image-not-used
                                     validate-tool-not-used-in-public-apps]]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure-commons.exception-util :as cxu]
            [kameleon.uuids :refer [uuidify]]
            [korma.core :as sql])
  (:import java.util.Base64))

(defn- encode-auth [registry]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes
                    (json/encode (select-keys registry [:username :password])))))

(defn- auth-info [image-name]
  (let [registry (get-registry-from-image image-name)]
    (when registry
      (encode-auth registry))))

(defn- public-image-info
  "Returns a map containing only publicly-permissible image info (no auth)"
  [image-uuid]
  (first (sql/select container-images
                     (sql/fields :name :tag :url :deprecated :id :osg_image_path)
                     (sql/where {:id (uuidify image-uuid)}))))

(defn image-info
  "Returns a map containing information about a container image. Info is looked up by the image UUID."
  [image-uuid & {:keys [auth?] :or {auth? false}}]
  (let [image (public-image-info image-uuid)
        auth (and auth? (auth-info (:name image)))]
    (if auth
      (assoc image :auth auth)
      image)))

(defn list-images
  "Returns a list of all defined images."
  []
  {:container_images
   (sql/select container-images
               (sql/fields :name :tag :url :deprecated :id :osg_image_path))})

(defn image-public-tools
  [id]
  (assert-not-nil [:image_id id] (image-info id))
  {:tools (map remove-nil-vals (get-tools-in-public-apps-by-image-id id))})

(defn- tool-image-info
  "Returns a map containing information about a container image. Info is looked up by the tool UUID"
  [tool-uuid & {:keys [auth?] :or {auth? false}}]
  (let [image-id (:container_images_id
                  (first (sql/select tools
                                     (sql/fields :container_images_id)
                                     (sql/where {:id (uuidify tool-uuid)}))))]
    (image-info image-id :auth? auth?)))

(defn find-matching-image
  "Finds the image matching the given name and tag in the container_images table."
  [{:keys [name tag osg_image_path] :or {tag "latest"}}]
  (first (sql/select container-images
                     (sql/where {:name           name
                                 :tag            tag
                                 :osg_image_path osg_image_path}))))

(defn find-or-add-image-info
  "Finds or inserts an image with the given name and tag in the container_images table."
  [{:keys [name tag url osg_image_path] :or {tag "latest"} :as image-map}]
  (if-let [existing-image (find-matching-image image-map)]
    existing-image
    (sql/insert container-images (sql/values {:name           name
                                              :tag            tag
                                              :url            url
                                              :osg_image_path osg_image_path}))))

(defn- image-id
  "Returns the UUID used as the primary key in the container_images table."
  [image-map]
  (:id (find-matching-image image-map)))

(defn- find-or-add-image-id
  [image]
  (:id (find-or-add-image-info image)))

(defn modify-image-info
  "Updates the record for a container image. Basically, just allows you to set a new URL
   at this point."
  [image-id user overwrite-public image-info]
  (let [update-info (select-keys image-info [:name :tag :url :deprecated :osg_image_path])]
    (when-not (empty? update-info)
      (when-not overwrite-public
        (validate-image-not-used-in-public-apps image-id))
      (log/warn user "updating image" image-id image-info)
      (sql/update container-images
                  (sql/set-fields update-info)
                  (sql/where (= :id (uuidify image-id)))))
    (first
     (sql/select container-images
                 (sql/where (= :id (uuidify image-id)))))))

(defn delete-image
  [id user]
  (validate-image-not-used id)
  (log/warn user "deleting image" id)
  (sql/delete container-images (sql/where {:id id}))
  nil)

(defn- device-mapping?
  "Returns true if the combination of container_settings UUID, host-path, and
   container-path already exists in the container_devices table."
  [settings-uuid host-path container-path]
  (pos? (count (sql/select container-devices (sql/where (and (= :container_settings_id (uuidify settings-uuid))
                                                             (= :host_path host-path)
                                                             (= :container_path container-path)))))))

(defn- add-device
  "Associates a device with the given container_settings UUID."
  [settings-uuid device-map]
  (when (device-mapping? settings-uuid (:host_path device-map) (:container_path device-map))
    (let [{host-path :host_path container-path :container_path} device-map]
      (cxu/exists (str "device mapping already exists: " settings-uuid " " host-path " " container-path))))
  (sql/insert container-devices
              (sql/values (merge
                           (select-keys device-map [:host_path :container_path])
                           {:container_settings_id (uuidify settings-uuid)}))))

(defn- volume-mapping?
  "Returns true if the combination of container_settings UUID, host-path, and
   container-path already exists in the database."
  [settings-uuid host-path container-path]
  (pos? (count (sql/select container-volumes
                           (sql/where (and (= :container_settings_id (uuidify settings-uuid))
                                           (= :host_path host-path)
                                           (= :container_path container-path)))))))

(defn- add-volume
  "Adds a volume record to the database for the specified container_settings UUID."
  [settings-uuid volume-map]
  (when (volume-mapping? settings-uuid (:host_path volume-map) (:container_path volume-map))
    (let [{host-path :host_path container-path :container_path} volume-map]
      (cxu/exists (str "volume mapping already exists: " settings-uuid " " host-path " " container-path))))
  (sql/insert container-volumes
              (sql/values (merge
                           (select-keys volume-map [:host_path :container_path])
                           {:container_settings_id (uuidify settings-uuid)}))))

(defn- data-container
  "Returns a map describing a data container."
  [data-container-id]
  (assert-not-nil [:data-container-id data-container-id]
                  (first
                   (sql/select data-containers
                               (sql/fields :id :name_prefix :read_only)
                               (sql/with container-images (sql/fields :name :tag :url))
                               (sql/where {:id data-container-id})))))

(defn- add-data-container
  [data-container-info]
  (let [image-uuid (find-or-add-image-id data-container-info)
        insert-values (assoc (select-keys data-container-info [:name_prefix :read_only])
                             :container_images_id image-uuid)]
    (sql/insert data-containers (sql/values insert-values))))

(defn modify-data-container
  "Modifies a data container based on the update map."
  [data-container-id {:keys [name] :as data-container-info}]
  (when (data-container data-container-id)
    (transaction
     (let [container-images-id (when name (find-or-add-image-id data-container-info))
           umap (-> data-container-info
                    (select-keys [:name_prefix :read_only])
                    (assoc :container_images_id container-images-id)
                    remove-nil-vals)]
       (when-not (empty? umap)
         (sql/update data-containers
                     (sql/set-fields umap)
                     (sql/where {:id data-container-id})))
       (data-container data-container-id)))))

(defn- find-data-container-id
  "Returns the UUID used as the primary key in the data_containers table."
  [data-container-info]
  (:id (first
        (sql/select data-containers
                    (sql/where (assoc (select-keys data-container-info [:name_prefix :read_only])
                                      :container_images_id (image-id data-container-info)))))))

(defn volumes-from-mapping?
  "Returns true if the combination of the container_settings UUID and container
   already exists in the container_volumes_from table."
  [settings-uuid data-container-uuid]
  (pos? (count (sql/select container-volumes-from
                           (sql/where {:container_settings_id (uuidify settings-uuid)
                                       :data_containers_id     (uuidify data-container-uuid)})))))

(defn- volumes-from-settings
  [settings-uuid data-container-uuid]
  (first (sql/select container-volumes-from
                     (sql/fields :id)
                     (sql/with data-containers (sql/fields :name_prefix :read_only)
                               (sql/with container-images (sql/fields :name :tag :url)))
                     (sql/where {:container_settings_id (uuidify settings-uuid)
                                 :data_containers_id     (uuidify data-container-uuid)}))))

(defn- add-volumes-from
  "Adds a record to container_volumes_from associated with the given
   container_settings UUID."
  [settings-uuid data-container-uuid]
  (sql/insert container-volumes-from
              (sql/values {:container_settings_id (uuidify settings-uuid)
                           :data_containers_id    (uuidify data-container-uuid)})))

(defn- settings?
  "Returns true if the given UUID is associated with a set of container settings."
  [settings-uuid]
  (pos? (count (sql/select container-settings (sql/where {:id (uuidify settings-uuid)})))))

(defn- filter-container-settings
  [settings-map]
  (select-keys settings-map
               [:pids_limit
                :cpu_shares
                :memory_limit
                :min_memory_limit
                :min_cpu_cores
                :max_cpu_cores
                :min_gpus
                :max_gpus
                :min_disk_space
                :network_mode
                :working_directory
                :name
                :entrypoint
                :tools_id
                :interactive_apps_proxy_settings_id
                :skip_tmp_mount
                :uid
                :id]))

(defn- add-settings
  "Adds a new settings record to the database based on the parameter map."
  [settings-map]
  (sql/insert container-settings
              (sql/values (filter-container-settings settings-map))))

(defn tool-has-settings?
  "Returns true if the given tool UUID has some container settings associated with it."
  [tool-uuid]
  (pos? (count (sql/select container-settings (sql/where {:tools_id (uuidify tool-uuid)})))))

(defn- modify-settings
  "Modifies an existing set of container settings. Requires the container-settings-uuid
   and a new set of values."
  [settings-uuid settings-map]
  (when-not (settings? settings-uuid)
    (cxu/not-found (str "Container settings do not exist for UUID: " settings-uuid)))
  (let [values (filter-container-settings settings-map)]
    (sql/update container-settings
                (sql/set-fields values)
                (sql/where {:id (uuidify settings-uuid)}))))

(defn- filter-returns
  [retval]
  (-> retval remove-nil-vals remove-empty-vals))

(defn- add-data-container-auth
  "Adds authentication information to a list of data containers."
  [containers & {:keys [auth?] :or {auth? false}}]
  (if auth?
    (mapv (fn [{registry-name :name :as container}]
            (filter-returns (assoc container :auth (auth-info registry-name))))
          containers)
    containers))

(defn- add-interapps-info
  [container-settings]
  (-> container-settings
      (assoc :interactive_apps (first (sql/select interapps-proxy-settings
                                                  (sql/where {:id (:interactive_apps_proxy_settings_id container-settings)}))))
      (dissoc :interactive_apps_proxy_settings_id)))

(defn tool-container-info
  "Returns container info associated with a tool or nil. This is used to build
  the JSON map that is passed down to the JEX. If you make changes to the
  container-related parts of the DE database schema, you'll likely need to make
  changes here."
  [tool-uuid & {:keys [auth?] :or {auth? false}}]
  (let [id (uuidify tool-uuid)]
    (when (tool-has-settings? id)
      (->  (sql/select container-settings
                       (sql/fields :id
                                   :pids_limit
                                   :cpu_shares
                                   :memory_limit
                                   :min_memory_limit
                                   :min_cpu_cores
                                   :max_cpu_cores
                                   :min_gpus
                                   :max_gpus
                                   :min_disk_space
                                   :network_mode
                                   :name
                                   :working_directory
                                   :interactive_apps_proxy_settings_id
                                   :skip_tmp_mount
                                   :entrypoint
                                   :uid)
                       (sql/with container-devices
                                 (sql/fields :host_path :container_path :id))
                       (sql/with container-volumes
                                 (sql/fields :host_path :container_path :id))
                       (sql/with container-volumes-from
                                 (sql/fields :id)
                                 (sql/with data-containers
                                           (sql/fields :name_prefix :read_only)
                                           (sql/with container-images
                                                     (sql/fields :name :tag :url :deprecated :osg_image_path))))
                       (sql/with ports
                                 (sql/fields :host_port :container_port :bind_to_host :id))
                       (sql/where {:tools_id id}))
           first
           add-interapps-info
           (update :container_volumes_from add-data-container-auth :auth? auth?)
           (merge {:image (tool-image-info tool-uuid :auth? auth?)})
           filter-returns))))

(defn- add-settings-volumes-from
  [settings-id vf-map]
  (transaction
   (let [data-container-id (or (find-data-container-id vf-map)
                               (:id (add-data-container vf-map)))]
     (when-not (volumes-from-mapping? settings-id data-container-id)
       (add-volumes-from settings-id data-container-id))
     (volumes-from-settings settings-id data-container-id))))

(defn- port-mapping?
  "Returns true if the the specific combination of fields exists in the
   database."
  [settings-uuid host-port container-port bind-to-host]
  (pos? (count (sql/select ports
                           (sql/where (and (= :container_settings_id (uuidify settings-uuid))
                                           (= :host_port host-port)
                                           (= :container_port container-port)
                                           (= :bind_to_host bind-to-host)))))))

(defn- add-port
  [settings-uuid {host-port :host_port container-port :container_port bind-to-host :bind_to_host :as port-map}]
  (when (port-mapping? settings-uuid host-port container-port bind-to-host)
    (cxu/exists (str "port mapping already exists: " settings-uuid " " host-port " " container-port " " bind-to-host)))
  (sql/insert ports
              (sql/values (merge
                           (select-keys port-map [:host_port :container_port :bind_to_host])
                           {:container_settings_id (uuidify settings-uuid)}))))

(defn- filter-proxy-settings
  [proxy-settings]
  (select-keys proxy-settings
               [:id
                :image
                :name
                :frontend_url
                :cas_url
                :cas_validate
                :ssl_cert_path
                :ssl_key_path]))

(defn- add-proxy-settings
  "Adds a new proxy settings record to the database based on the parameter map"
  [proxy-settings]
  (sql/insert interapps-proxy-settings
              (sql/values (filter-proxy-settings proxy-settings))))

(defn add-tool-container
  [tool-uuid info-map]
  (when (tool-has-settings? tool-uuid)
    (cxu/exists (str "Tool " tool-uuid " already has container settings.")))
  (let [devices        (:container_devices info-map)
        volumes        (:container_volumes info-map)
        vfs            (:container_volumes_from info-map)
        ports          (:container_ports info-map)
        proxy-settings (:interactive_apps info-map)
        info-map       (assoc info-map :tools_id (uuidify tool-uuid))]
    (log/warn "adding container information for tool" tool-uuid ":" info-map)
    (transaction
     (let [img-id        (find-or-add-image-id (:image info-map))
           settings-map  (add-settings info-map)
           settings-uuid (:id settings-map)]
       (update-tool {:id tool-uuid :container_images_id img-id})
       (when-not (nil? proxy-settings)
         (modify-settings settings-uuid
                          {:interactive_apps_proxy_settings_id
                           (uuidify (:id (add-proxy-settings proxy-settings)))}))
       (doseq [d devices]
         (add-device settings-uuid d))
       (doseq [v volumes]
         (add-volume settings-uuid v))
       (doseq [vf vfs]
         (add-settings-volumes-from settings-uuid vf))
       (doseq [p ports]
         (add-port settings-uuid p))
       (tool-container-info tool-uuid)))))

(defn set-tool-container
  "Removes all existing container settings for the given tool-id, replacing them with the given settings."
  [tool-id
   overwrite-public
   {:keys [container_devices container_volumes container_volumes_from container_ports interactive_apps] :as settings}]
  (when-not overwrite-public
    (validate-tool-not-used-in-public-apps tool-id))
  (transaction
   (sql/delete container-settings (sql/where {:tools_id tool-id}))
   (let [img-id      (find-or-add-image-id (:image settings))
         settings    (assoc settings :tools_id tool-id)
         settings-id (:id (add-settings settings))]
     (update-tool {:id tool-id :container_images_id img-id})
     (when-not (nil? interactive_apps)
       (modify-settings settings-id
                        {:interactive_apps_proxy_settings_id
                         (uuidify (:id (add-proxy-settings interactive_apps)))}))
     (doseq [device container_devices]
       (add-device settings-id device))
     (doseq [volume container_volumes]
       (add-volume settings-id volume))
     (doseq [volume container_volumes_from]
       (add-settings-volumes-from settings-id volume))
     (doseq [port container_ports]
       (add-port settings-id port))

     (tool-container-info tool-id))))
