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
