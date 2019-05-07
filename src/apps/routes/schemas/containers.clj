(ns apps.routes.schemas.containers
  (:use [common-swagger-api.schema :only [->optional-param describe]]
        [common-swagger-api.schema.containers]
        [apps.routes.params :only [SecuredQueryParams]])
  (:require [schema.core :as s])
  (:import (java.util UUID)))

(s/defschema Images
  (describe
    {:container_images [Image]}
    "A list of container images."))

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

(s/defschema Entrypoint
  (describe
   {:entrypoint (s/maybe s/Str)}
   "The entrypoint for a tool container"))

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

(def VolumesFromIdParam
  (describe
    UUID
    "A volume from's UUID."))

(s/defschema VolumesFromList
  (describe
   {:container_volumes_from [VolumesFrom]}
   "The list of VolumeFroms associated with a tool's container."))
