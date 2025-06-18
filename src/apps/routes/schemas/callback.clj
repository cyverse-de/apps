(ns apps.routes.schemas.callback
  (:require [common-swagger-api.schema :refer [describe]]
            [schema.core :refer [defschema]]))

(defschema DeJobStatusUpdate
  {:uuid (describe String "The external identifier of the analysis")})
