(ns apps.containers-test
  (:require
   [apps.containers :refer [find-or-add-image-info image-info tool-has-settings? volumes-from-mapping?]]
   [apps.persistence.entities
    :refer [container-devices
            container-images
            container-settings
            container-volumes
            container-volumes-from
            data-containers
            tools]]
   [apps.test-fixtures :refer [run-integration-tests with-test-db]]
   [clojure.test :refer [deftest is use-fixtures]]
   [korma.core :as sql]))

;; Re-def private functions so they can be tested in this namespace.
(def tool-image-info #'apps.containers/tool-image-info)
(def image-id #'apps.containers/image-id)
(def device-mapping? #'apps.containers/device-mapping?)
(def add-device #'apps.containers/add-device)
(def volume-mapping? #'apps.containers/volume-mapping?)
(def add-volume #'apps.containers/add-volume)
(def add-data-container #'apps.containers/add-data-container)
(def add-volumes-from #'apps.containers/add-volumes-from)
(def settings? #'apps.containers/settings?)
(def add-settings #'apps.containers/add-settings)

(def ^:dynamic image-info-map nil)
(def ^:dynamic data-container-map nil)
(def ^:dynamic tool-map nil)
(def ^:dynamic settings-map nil)
(def ^:dynamic devices-map nil)
(def ^:dynamic volume-map nil)
(def ^:dynamic volumes-from-map nil)

(defn- add-image-info-map []
  (find-or-add-image-info {:name "discoenv/de-db" :tag "latest" :url "https://www.google.com"}))

(defn- add-data-container-map []
  (add-data-container {:name        "discoenv/foo"
                       :tag         "latest"
                       :url         "https://www.google.com"
                       :name_prefix "foo"
                       :read_only   true}))

(defn- get-tool-map []
  (first (sql/select tools (sql/where {:name "notreal"}))))

(defn- add-settings-map [tool-id]
  (add-settings {:name              "test"
                 :cpu_shares        1024
                 :memory_limit      2048
                 :network_mode      "bridge"
                 :working_directory "/work"
                 :tools_id          tool-id}))

(defn- add-devices-map [settings-id]
  (add-device settings-id {:host_path "/dev/null" :container_path "/dev/yay"}))

(defn- add-volume-map [settings-id]
  (add-volume settings-id {:host_path "/tmp" :container_path "/foo"}))

(defn- add-volumes-from-map [settings-id data-container-id]
  (add-volumes-from settings-id data-container-id))

(defn- add-test-data []
  (let [image-info     (add-image-info-map)
        data-container (add-data-container-map)
        tool           (get-tool-map)
        settings       (add-settings-map (:id tool))
        device         (add-devices-map (:id settings))
        volume         (add-volume-map (:id settings))
        volumes-from   (add-volumes-from-map (:id settings) (:id data-container))]
    (vector image-info data-container tool settings device volume volumes-from)))

(defn- remove-container-image-references []
  (sql/update tools
              (sql/set-fields {:container_images_id nil})
              (sql/where {:container_images_id (:id image-info-map)})))

(defn- remove-test-data []
  (sql/delete container-volumes-from (sql/where {:id (:id volumes-from-map)}))
  (sql/delete container-volumes (sql/where {:id (:id volume-map)}))
  (sql/delete container-devices (sql/where {:id (:id devices-map)}))
  (sql/delete container-settings (sql/where {:id (:id settings-map)}))
  (sql/delete data-containers (sql/where {:id (:id data-container-map)}))
  (remove-container-image-references)
  (sql/delete container-images (sql/where {:id (:id image-info-map)})))

(defn- with-test-data [f]
  (let [[image-info data-container tool settings device volume volumes-from] (add-test-data)]
    (binding [image-info-map     image-info
              data-container-map data-container
              tool-map           tool
              settings-map       settings
              devices-map        device
              volume-map         volume
              volumes-from-map   volumes-from]
      (f)
      (remove-test-data))))

(use-fixtures :once with-test-db run-integration-tests)
(use-fixtures :each with-test-data)

(deftest image-tests
  (is (not (nil? (image-id {:name "discoenv/de-db" :tag "latest"}))))

  (is (= {:name "discoenv/de-db" :tag "latest" :url "https://www.google.com" :deprecated false}
         (dissoc (image-info (image-id {:name "discoenv/de-db" :tag "latest"})) :id))))

(deftest settings-tests
  (is (not (nil? (:id settings-map))))

  (is (settings? (:id settings-map)))

  (is (tool-has-settings? (:id tool-map))))

(deftest devices-tests
  (is (not (nil? (:id devices-map))))

  (is (device-mapping? (:id settings-map) "/dev/null" "/dev/yay")))

(deftest volumes-tests
  (is (not (nil? (:id volume-map))))

  (is (volume-mapping? (:id settings-map) "/tmp" "/foo")))

(deftest volumes-from-test
  (is (not (nil? (:id volumes-from-map))))

  (is (volumes-from-mapping? (:id settings-map) (:id data-container-map))))

(deftest updated-tool-tests
  (sql/update tools
              (sql/set-fields {:container_images_id (:id image-info-map)})
              (sql/where {:id (:id tool-map)}))
  (let [updated-tool (first (sql/select tools (sql/where {:id (:id tool-map)})))]
    (is (not (nil? (:id updated-tool))))

    (is (= image-info-map (tool-image-info (:id updated-tool))))))
