(ns apps.routes.schemas.containers
  (:use [common-swagger-api.schema :only [->optional-param describe]]
        [common-swagger-api.schema.containers]
        [apps.routes.params :only [SecuredQueryParams]])
  (:require [apps.util.coercions :as coercions]
            [schema.core :as s])
  (:import (java.util UUID)))

(s/defschema Images
  (describe
    {:container_images [Image]}
    "A list of container images."))

(s/defschema NewImage
  (describe
   (dissoc Image :id)
   "The values needed to add a new image to a tool."))

(s/defschema ImageId
  (describe
    UUID
    "A container image UUID."))

(s/defschema ImageUpdateRequest
  (describe (->optional-param NewImage :name) "An Image update request."))

(s/defschema ImageUpdateParams
  (merge SecuredQueryParams
    {(s/optional-key :overwrite-public)
     (describe Boolean "Flag to force updates of images used by public tools.")}))

(defn coerce-settings-long-values
  "Converts any values in the given settings map that should be a Long, according to the Settings schema."
  [settings]
  (-> settings
      (coercions/coerce-string->long :memory_limit)
      (coercions/coerce-string->long :min_memory_limit)
      (coercions/coerce-string->long :min_disk_space)))

(s/defschema Entrypoint
  (describe
   {:entrypoint (s/maybe s/Str)}
   "The entrypoint for a tool container"))

(s/defschema NewSettings
  (describe
   (->optional-param Settings :id)
   "The values needed to add a new container to a tool."))

(s/defschema CPUShares
  (describe
   {:cpu_shares (s/maybe Integer)}
   "The shares of the CPU that the tool container will receive."))

(s/defschema MemoryLimit
  (describe
   {:memory_limit (s/maybe Long)}
   "The amount of memory (in bytes) that the tool container is restricted to."))

(s/defschema MinMemoryLimit
  (describe
   {:min_memory_limit (s/maybe Long)}
   "The minimum about of memory (in bytes) that is required to run the tool container."))

(s/defschema MinCPUCores
  (describe
   {:min_cpu_cores (s/maybe Integer)}
   "The minimum number of CPU cores needed to run the tool container."))

(s/defschema MinDiskSpace
  (describe
   {:min_disk_space (s/maybe Long)}
   "The minimum amount of disk space needed to run the tool container."))

(s/defschema NetworkMode
  (describe
   {:network_mode (s/maybe s/Str)}
   "The network mode for the tool container."))

(s/defschema WorkingDirectory
  (describe
   {:working_directory (s/maybe s/Str)}
   "The working directory in the tool container."))

(s/defschema ContainerName
  (describe
   {:name (s/maybe s/Str)}
   "The name given to the tool container."))

(s/defschema NewDevice
  (describe
   (dissoc Device :id)
   "The map needed to add a device to a container."))

(s/defschema DeviceHostPath
  (describe
   {:host_path s/Str}
   "A device's path on the container host."))

(s/defschema DeviceContainerPath
  (describe
   {:container_path s/Str}
   "A device's path inside the tool container."))

(def DeviceIdParam
  (describe
    UUID
    "A device's UUID."))

(s/defschema Devices
  (describe
   {:container_devices [Device]}
   "A list of devices associated with a tool's container."))

(s/defschema NewVolume
  (describe
   (dissoc Volume :id)
   "A map for adding a new volume to a container."))

(def VolumeIdParam
  (describe
    UUID
    "A volume's UUID."))

(s/defschema Volumes
  (describe
   {:container_volumes [Volume]}
   "A list of Volumes associated with a tool's container."))

(s/defschema VolumeHostPath
  (describe
   {:host_path s/Str}
   "The path to a bind mounted volume on the host machine."))

(s/defschema VolumeContainerPath
  (describe
   {:container_path s/Str}
   "The path to a bind mounted volume in the tool container."))

(s/defschema DataContainers
  (describe
   {:data_containers [DataContainer]}
   "A list of data containers."))

(s/defschema DataContainerIdParam
  (describe
    UUID
    "A data container's UUID."))

(s/defschema DataContainerUpdateRequest
  (describe
    (-> DataContainer
        (->optional-param :name_prefix)
        (->optional-param :name)
        (dissoc :id))
    "A map for updating data container settings."))

(s/defschema NewVolumesFrom
  (describe
   (dissoc VolumesFrom :id)
   "A map for adding a new container from which to bind mount volumes."))

(def VolumesFromIdParam
  (describe
    UUID
    "A volume from's UUID."))

(s/defschema VolumesFromList
  (describe
   {:container_volumes_from [VolumesFrom]}
   "The list of VolumeFroms associated with a tool's container."))

(s/defschema NewPort
  (describe
   (dissoc Port :id)
   "A map for adding a new port configuration to a tool container."))

(s/defschema NewProxySettings
  (describe
   (dissoc ProxySettings :id)
   "A map for adding new interactive app reverse proxy settings."))

(s/defschema NewToolContainer
  (describe
   (merge
    NewSettings
    {DevicesParamOptional       [NewDevice]
     VolumesParamOptional       [NewVolume]
     VolumesFromParamOptional   [NewVolumesFrom]
     PortsParamOptional         [NewPort]
     ProxySettingsParamOptional NewProxySettings
     :image                     NewImage})
   "The settings for adding a new full container definition to a tool."))
