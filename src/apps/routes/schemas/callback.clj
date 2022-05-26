(ns apps.routes.schemas.callback
  (:use [common-swagger-api.schema :only [describe]]
        [schema.core :only [defschema]]))

(defschema DeJobStatusUpdate
  {:uuid (describe String "The external identifier of the analysis")})
